package com.vseeheat.collector;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/** Downloads reviewed compatibility data without downloading executable builder code. */
final class CompatibilityUpdates {
    private static final String CATALOG_URL =
            "https://raw.githubusercontent.com/torrent329/vsee-app-builder/main/compatibility/catalog.json";
    private static final String PUBLIC_KEY_BASE64 =
            "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEA2g0ZBp3b32qph6OnPibezb8jvYxY/d8FdW8MGpX09qucZppGhj2gj0e/AvLoC7MKPFoA8M4TV/tnwberp8bf/V5ifDqIU3hgqwNWbCXh0t5oSCwwkgAvxU+dyVSdkCW3nqPnqrnotGVxxkcWOQ1lz7hxAuUjZIj9nG9dgcIgvArMddMvZU2Qeldxut83NAuf+UKYm+GEdqjSx6ucnnayzVwaem54GFLyr3XRhPo/tNenwaFWowfmh7qzsVsuP4fFyulZVkxCSUbCzd3WYO2obC7sf56bHGx6EqueWvsPjkyXbbyXUIxvpApD2fodf9+qRD71W5/URSx5hFzk810vVr/hpxABeY8iL1OaNfjEvFEyYcQcsPJtzCz/rU3OpGD0Ln3wNr0OjDPtbFnw/lX9b1ao/kjVGrUmymXxFUGRnlYsh3/LaAgx7S2RVWcYGcnJjSEVrMpPreDPGX/y9uziE19AQzt8yKUq6exTWWtM4MWzRGdlntfLwHWV36ajfMarAgMBAAE=";
    private static final String PREFS = "compatibility_updates";
    private static final int MAX_CATALOG_BYTES = 64 * 1024;
    private static final int MAX_TEMPLATE_BYTES = 64 * 1024 * 1024;

    private CompatibilityUpdates() {}

    static void refreshAndPrepare(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // This runs when the owner explicitly selects Check compatibility.
        // Always fetch so a newly reviewed app update is available immediately.
        try {
            String envelope = new String(fetch(CATALOG_URL, MAX_CATALOG_BYTES),
                    StandardCharsets.UTF_8);
            JSONObject payload = verifiedPayload(envelope);
            int revision = payload.getInt("revision");
            if (revision >= prefs.getInt("highest_revision", 0)) {
                prefs.edit().putString("catalog", envelope)
                        .putInt("highest_revision", revision).commit();
            }
        } catch (Exception ignored) {
            // Offline or invalid updates never replace the last verified catalog.
        }
        try {
            JSONObject payload = cachedPayload(context);
            if (payload == null) return;
            JSONArray entries = payload.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String packageName = entry.getString("originalPackage");
                PackageInfo installed;
                try {
                    installed = context.getPackageManager().getPackageInfo(packageName, 0);
                } catch (Exception ignored) {
                    continue;
                }
                if (entry.getString("versionName").equals(installed.versionName)
                        && entry.getLong("versionCode") == installed.getLongVersionCode()
                        && !entry.getString("originalSha256").equals(
                                builtInOriginalHash(packageName, installed.versionName,
                                        installed.getLongVersionCode()))) {
                    try {
                        ensureTemplate(context, entry);
                    } catch (Exception ignored) {
                        // A missing template affects only this app version.
                    }
                }
            }
        } catch (Exception ignored) {
            // An update can be retried on the next compatibility check.
        }
    }

    static HeatAppSpec.Spec resolve(Context context, String packageName,
                                    String versionName, long versionCode) {
        try {
            JSONObject payload = cachedPayload(context);
            if (payload == null) return null;
            JSONArray entries = payload.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                if (!packageName.equals(entry.getString("originalPackage"))
                        || !versionName.equals(entry.getString("versionName"))
                        || versionCode != entry.getLong("versionCode")) continue;
                String templateHash = entry.getString("templateSha256");
                File template = templateFile(context, templateHash);
                if (!template.isFile() || !templateHash.equals(sha256(template))) return null;
                HeatAppSpec.Spec base = base(packageName);
                String outputName = packageName.equals("com.google.heatlive")
                        ? "Heat-Live-V6-Owner-" + versionName + ".apk"
                        : base.outputName.replace(base.versionName + ".apk",
                                versionName + ".apk");
                return new HeatAppSpec.Spec(base.label, packageName, versionName,
                        versionCode, entry.getInt("minSdk"),
                        entry.getLong("originalBytes"),
                        entry.getString("originalSha256"),
                        entry.getString("payloadSha256"),
                        "remote:" + templateHash, templateHash,
                        entry.getInt("templateEntries"),
                        entry.getString("lib32Sha256"),
                        entry.getString("lib64Sha256"),
                        entry.getInt("pin32Offset"),
                        entry.getInt("pin64Offset"), outputName);
            }
        } catch (Exception ignored) { /* Invalid updates never authorize a build. */ }
        return null;
    }

    static InputStream openTemplate(Context context, HeatAppSpec.Spec spec)
            throws Exception {
        if (!spec.templateAsset.startsWith("remote:")) {
            return context.getAssets().open(spec.templateAsset);
        }
        File file = templateFile(context, spec.templateSha256);
        if (!file.isFile() || !spec.templateSha256.equals(sha256(file))) {
            throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
        }
        return new FileInputStream(file);
    }

    private static JSONObject cachedPayload(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String envelope = prefs.getString("catalog", null);
        if (envelope == null) return null;
        JSONObject payload = verifiedPayload(envelope);
        return payload.getInt("revision") >= prefs.getInt("highest_revision", 0)
                ? payload : null;
    }

    private static JSONObject verifiedPayload(String envelope) throws Exception {
        JSONObject signed = new JSONObject(envelope);
        byte[] payloadBytes = Base64.decode(signed.getString("payload"), Base64.DEFAULT);
        byte[] signatureBytes = Base64.decode(signed.getString("signature"), Base64.DEFAULT);
        if (payloadBytes.length == 0 || payloadBytes.length > MAX_CATALOG_BYTES
                || signatureBytes.length != 384) throw new IllegalArgumentException();
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(key);
        verifier.update(payloadBytes);
        if (!verifier.verify(signatureBytes)) throw new IllegalArgumentException();
        JSONObject payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        if (payload.getInt("format") != 1 || payload.getInt("revision") < 1) {
            throw new IllegalArgumentException();
        }
        JSONArray entries = payload.getJSONArray("entries");
        if (entries.length() > 30) throw new IllegalArgumentException();
        for (int i = 0; i < entries.length(); i++) validate(entries.getJSONObject(i));
        return payload;
    }

    private static void validate(JSONObject entry) throws Exception {
        String packageName = entry.getString("originalPackage");
        base(packageName);
        String version = entry.getString("versionName");
        if (!version.matches("[0-9]+(\\.[0-9]+){1,3}") || version.length() > 24
                || entry.getLong("versionCode") <= 0
                || entry.getLong("originalBytes") <= 0
                || entry.getLong("originalBytes") > HeatLiveGate.MAX_APK_BYTES
                || entry.getInt("minSdk") < 26 || entry.getInt("minSdk") > 34
                || entry.getInt("templateEntries") < 100
                || entry.getInt("templateEntries") > 3000
                || entry.getInt("pin32Offset") < 0
                || entry.getInt("pin64Offset") < 0) throw new IllegalArgumentException();
        for (String field : new String[]{"originalSha256", "payloadSha256",
                "templateSha256", "lib32Sha256", "lib64Sha256"}) {
            if (!entry.getString(field).matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException();
            }
        }
        String url = entry.getString("templateUrl");
        if (!url.matches("https://raw\\.githubusercontent\\.com/torrent329/"
                + "vsee-app-builder/main/(compatibility/templates/"
                + "|heat-live-collector/app/src/main/assets/)"
                + "[A-Za-z0-9._-]+\\.apk")) {
            throw new IllegalArgumentException();
        }
    }

    private static HeatAppSpec.Spec base(String packageName) {
        if ("com.google.heatlive".equals(packageName)) {
            return new HeatAppSpec.Spec("Heat Live", packageName, "5.9.5", 595, 26,
                    1, "", "", "", "", 1, "", "", 0, 0,
                    "Heat-Live-V6-Owner-5.9.5.apk");
        }
        for (HeatAppSpec.Spec spec : HeatAppSpec.OTHER) {
            if (spec.originalPackage.equals(packageName)) return spec;
        }
        throw new IllegalArgumentException();
    }

    private static String builtInOriginalHash(String packageName,
                                              String version, long code) {
        if ("com.google.heatlive".equals(packageName)) {
            return HeatLiveGate.builtInOriginalHash(version, code);
        }
        HeatAppSpec.Spec spec = base(packageName);
        return spec.versionName.equals(version) && spec.versionCode == code
                ? spec.originalSha256 : null;
    }

    private static void ensureTemplate(Context context, JSONObject entry) throws Exception {
        String expected = entry.getString("templateSha256");
        File file = templateFile(context, expected);
        if (file.isFile() && expected.equals(sha256(file))) return;
        File temp = new File(file.getParentFile(), expected + ".pending");
        try {
            String actual = fetchToFile(entry.getString("templateUrl"), temp,
                    MAX_TEMPLATE_BYTES);
            if (!expected.equals(actual)) throw new IllegalArgumentException();
            if (file.exists() && !file.delete()) throw new IllegalArgumentException();
            if (!temp.renameTo(file)) throw new IllegalArgumentException();
        } finally {
            temp.delete();
        }
    }

    private static File templateFile(Context context, String hash) {
        File folder = new File(context.getFilesDir(), "compatibility-templates");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException();
        return new File(folder, hash + ".apk");
    }

    private static byte[] fetch(String address, int limit) throws Exception {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() != 200
                    || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IllegalArgumentException();
            }
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (output.size() + count > limit) throw new IllegalArgumentException();
                    output.write(block, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String fetchToFile(String address, File file, int limit)
            throws Exception {
        HttpURLConnection connection = open(address);
        try {
            if (connection.getResponseCode() != 200
                    || !"https".equalsIgnoreCase(connection.getURL().getProtocol())) {
                throw new IllegalArgumentException();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int total = 0;
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] block = new byte[8192];
                int count;
                while ((count = input.read(block)) != -1) {
                    total += count;
                    if (total > limit) throw new IllegalArgumentException();
                    output.write(block, 0, count);
                    digest.update(block, 0, count);
                }
                output.getFD().sync();
            }
            if (total == 0) throw new IllegalArgumentException();
            return HeatLiveGate.toHex(digest.digest());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Vsee-App-Builder-Compatibility/1");
        return connection;
    }

    private static String sha256(File file) throws Exception {
        byte[] hash = HeatLiveGate.hashFile(file);
        return hash == null ? "" : HeatLiveGate.toHex(hash);
    }
}
