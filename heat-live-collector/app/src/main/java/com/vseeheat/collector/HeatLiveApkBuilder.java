package com.vseeheat.collector;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Offline exact-template builder; never starts or installs the generated app. */
final class HeatLiveApkBuilder {
    private static final String TEMPLATE_594 = "heat-live-template.apk";
    private static final String TEMPLATE_595 = "heat-live-595-template.apk";
    private static final String TEMPLATE_SHA256_594 =
            "47949636053c79567ef5c34ec898f3d4927b0d7c0655dfd1ca54c29fc33c30a9";
    private static final String TEMPLATE_SHA256_595 =
            "35be87a84f89322b2097bbbf97060e814d8363be5b2ec795acd9e5d121f5aeb1";
    private static final String PLACEHOLDER_SHA256 =
            "a10a25533957d04d21db6c5aae100b047350908bbe890f0ed4d30b937195f345";
    private static final String PROFILE_ENTRY = "assets/v6-owner-profile.json";
    private static final String LIB32 = "lib/armeabi-v7a/libjson.so";
    private static final String LIB64 = "lib/arm64-v8a/libjson.so";
    private static final String LIB32_SHA256 =
            "103d3ed4b0a219520c3911c4c46f2ff2fce23200067b15687678e42c2b5c46d5";
    private static final String LIB64_SHA256 =
            "003e750fd452c52b1ee524a98b794637f9cc7f481a2da6c527e0324bc3415ad6";
    private static final int PIN32_OFFSET = 731_414;
    private static final int PIN64_OFFSET = 1_006_780;
    private static final int ENTRY_COUNT = 1801;
    private HeatLiveApkBuilder() {}

    static Uri build(Context context) throws Exception {
        return build(context, Environment.DIRECTORY_DOWNLOADS);
    }

    static Uri build(Context context, String relativePath) throws Exception {
        HeatLiveGate.Result gate = HeatLiveGate.inspect(context);
        if (!gate.supported()) throw new BuildFailure("CHECK_FAILED");
        if (gate.remoteSpec != null) {
            HeatAppSpec.Spec update = gate.remoteSpec;
            if (update == null) throw new BuildFailure("CHECK_FAILED");
            JSONObject ownerProfile = PrivateProfileStore.loadFull(context,
                    gate.originalUid);
            OwnerSigningKeys.Material signing = OwnerSigningKeys.load(context);
            if (ownerProfile == null || signing == null) {
                throw new BuildFailure("FULL_PROFILE_MISSING");
            }
            verifyCurrentProfile(context, gate.originalUid, ownerProfile);
            return OtherHeatApkBuilder.build(context, update, ownerProfile,
                    signing, relativePath);
        }
        boolean is595 = "5.9.5".equals(gate.installedVersion);
        String templateAsset = is595 ? TEMPLATE_595 : TEMPLATE_594;
        String templateHash = is595 ? TEMPLATE_SHA256_595 : TEMPLATE_SHA256_594;
        String outputName = "Heat-Live-V6-Owner-" + gate.installedVersion + ".apk";
        JSONObject profile;
        try {
            profile = PrivateProfileStore.loadFull(context, gate.originalUid);
            if (profile == null) throw new IllegalStateException();
        } catch (Exception ignored) {
            throw new BuildFailure("FULL_PROFILE_MISSING");
        }
        verifyCurrentProfile(context, gate.originalUid, profile);
        OwnerSigningKeys.Material owner;
        try {
            owner = OwnerSigningKeys.load(context);
            if (owner == null) throw new IllegalStateException();
        } catch (Exception ignored) {
            throw new BuildFailure("OWNER_KEY_UNAVAILABLE");
        }
        File template = File.createTempFile("heat-template-", ".apk", context.getCacheDir());
        File unsigned = File.createTempFile("heat-unsigned-", ".apk", context.getCacheDir());
        File signed = File.createTempFile("heat-signed-", ".apk", context.getCacheDir());
        try {
            try (InputStream input = context.getAssets().open(templateAsset);
                 OutputStream output = new FileOutputStream(template)) {
                copy(input, output, 32 * 1024 * 1024);
            }
            if (!templateHash.equals(sha256(template))) {
                throw new BuildFailure("TEMPLATE_MISMATCH");
            }
            byte[] pin = ownerPin(owner.certificate);
            repackage(template, unsigned, profile.toString().getBytes(StandardCharsets.UTF_8), pin);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("owner",
                    owner.privateKey, Collections.singletonList(owner.certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(unsigned)
                    .setOutputApk(signed)
                    .setMinSdkVersion(26)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setV4SigningEnabled(false)
                    .setOtherSignersSignaturesPreserved(false)
                    .build().sign();
            ApkVerifier.Result verified = new ApkVerifier.Builder(signed)
                    .setMinCheckedPlatformVersion(26).build().verify();
            if (!verified.isVerified() || verified.getSignerCertificates().size() != 1
                    || !MessageDigest.isEqual(owner.certificate.getEncoded(),
                            verified.getSignerCertificates().get(0).getEncoded())) {
                throw new BuildFailure("SIGNATURE_VERIFY_FAILED");
            }
            HeatLiveGate.Result after = HeatLiveGate.inspect(context);
            if (!after.supported() || after.originalUid != gate.originalUid) {
                throw new BuildFailure("CHECK_FAILED");
            }
            verifyCurrentProfile(context, gate.originalUid, profile);
            return exportDownloads(context, signed, outputName, relativePath);
        } finally {
            template.delete();
            unsigned.delete();
            signed.delete();
        }
    }

    static void verifyCurrentProfile(Context context, int expectedUid,
                                             JSONObject profile) throws Exception {
        String identity = PrivilegedCollector.verifySaved(context, expectedUid,
                profile.getString("androidId"), profile.getString("randomSn"));
        if ("VERIFIED".equals(identity)) return;
        if ("IDENTITY_CHANGED".equals(identity)) {
            PrivateProfileStore.invalidateProfile(context);
            throw new BuildFailure("IDENTITY_CHANGED");
        }
        throw new BuildFailure("IDENTITY_CHECK_FAILED");
    }

    private static void repackage(File template, File unsigned,
                                  byte[] profile, byte[] newPin) throws Exception {
        try (ZipFile source = new ZipFile(template);
             CountingStream count = new CountingStream(new FileOutputStream(unsigned));
             ZipOutputStream target = new ZipOutputStream(count,
                     StandardCharsets.UTF_8)) {
            if (source.size() != ENTRY_COUNT) throw new BuildFailure("TEMPLATE_MISMATCH");
            Set<String> names = new HashSet<>();
            int changed = 0;
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                String name = original.getName();
                if (!names.add(name) || isSignatureEntry(name)) {
                    throw new BuildFailure("TEMPLATE_MISMATCH");
                }
                byte[] bytes;
                try (InputStream input = source.getInputStream(original)) {
                    bytes = readBounded(input, 32 * 1024 * 1024);
                }
                if (PROFILE_ENTRY.equals(name)) {
                    if (!PLACEHOLDER_SHA256.equals(sha256(bytes))) {
                        throw new BuildFailure("TEMPLATE_MISMATCH");
                    }
                    bytes = profile;
                    changed++;
                } else if (LIB32.equals(name) || LIB64.equals(name)) {
                    String expectedHash = LIB32.equals(name) ? LIB32_SHA256 : LIB64_SHA256;
                    int offset = LIB32.equals(name) ? PIN32_OFFSET : PIN64_OFFSET;
                    if (!expectedHash.equals(sha256(bytes)) || offset + 32 > bytes.length) {
                        throw new BuildFailure("TEMPLATE_MISMATCH");
                    }
                    byte[] existing = Arrays.copyOfRange(bytes, offset, offset + 32);
                    if (!Arrays.equals(existing,
                            "00000000000000000000000000000000"
                                    .getBytes(StandardCharsets.US_ASCII))) {
                        throw new BuildFailure("TEMPLATE_MISMATCH");
                    }
                    System.arraycopy(newPin, 0, bytes, offset, newPin.length);
                    changed++;
                }
                ZipEntry replacement = new ZipEntry(name);
                if (original.getTime() >= 0) replacement.setTime(original.getTime());
                replacement.setMethod(original.getMethod());
                if (original.getMethod() == ZipEntry.STORED) {
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    replacement.setSize(bytes.length);
                    replacement.setCompressedSize(bytes.length);
                    replacement.setCrc(crc.getValue());
                    replacement.setExtra(ZipAlignment.extraForStored(name,
                            original.getTime(), count.bytes));
                }
                target.putNextEntry(replacement);
                if (original.getMethod() == ZipEntry.STORED && count.bytes % 4 != 0) {
                    android.util.Log.e("VseeZipAlign", "Misaligned entry " + name
                            + " at offset " + count.bytes);
                    throw new BuildFailure("ZIP_ALIGNMENT_FAILED");
                }
                target.write(bytes);
                target.closeEntry();
            }
            if (changed != 3 || names.size() != ENTRY_COUNT) {
                throw new BuildFailure("TEMPLATE_MISMATCH");
            }
        }
    }

    static boolean isSignatureEntry(String name) {
        String upper = name.toUpperCase(java.util.Locale.US);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".RSA") || upper.endsWith(".DSA")
                    || upper.endsWith(".EC") || upper.endsWith(".SF")
                    || upper.equals("META-INF/MANIFEST.MF"));
    }

    static byte[] ownerPin(X509Certificate certificate) throws Exception {
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(certificate.getEncoded());
        String input = toHex(sha).toUpperCase(java.util.Locale.US) + "CADE1E35";
        byte[] md5 = MessageDigest.getInstance("MD5")
                .digest(input.getBytes(StandardCharsets.US_ASCII));
        String hex = toHex(md5);
        String table = "6BC104FE7328D95A";
        StringBuilder pin = new StringBuilder(32);
        for (int index = 0; index < hex.length(); index++) {
            pin.append(table.charAt(Character.digit(hex.charAt(index), 16)));
        }
        return pin.toString().getBytes(StandardCharsets.US_ASCII);
    }

    static Uri exportDownloads(Context context, File apk, String outputName,
                               String relativePath) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, outputName);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                "application/vnd.android.package-archive");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new BuildFailure("EXPORT_FAILED");
        try {
            try (InputStream input = new FileInputStream(apk);
                 OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new BuildFailure("EXPORT_FAILED");
                copy(input, output, 64 * 1024 * 1024);
            }
            ContentValues visible = new ContentValues();
            visible.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, visible, null, null) != 1) {
                throw new BuildFailure("EXPORT_FAILED");
            }
            return uri;
        } catch (Exception failure) {
            context.getContentResolver().delete(uri, null, null);
            throw failure;
        }
    }

    static void copy(InputStream input, OutputStream output, int maximum)
            throws IOException {
        byte[] block = new byte[8192];
        int total = 0;
        int size;
        while ((size = input.read(block)) != -1) {
            total += size;
            if (total > maximum) throw new IOException();
            output.write(block, 0, size);
        }
    }

    static byte[] readBounded(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output, maximum);
        return output.toByteArray();
    }

    static String sha256(File input) throws Exception {
        try (InputStream stream = new FileInputStream(input)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            int count;
            while ((count = stream.read(block)) != -1) digest.update(block, 0, count);
            return toHex(digest.digest());
        }
    }

    static String sha256(byte[] input) throws Exception {
        return toHex(MessageDigest.getInstance("SHA-256").digest(input));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(Character.forDigit((item & 0xff) >>> 4, 16));
            value.append(Character.forDigit(item & 0x0f, 16));
        }
        return value.toString();
    }

    static final class BuildFailure extends Exception {
        final String stage;

        BuildFailure(String stage) {
            this.stage = stage;
        }
    }

    static final class CountingStream extends FilterOutputStream {
        long bytes;

        CountingStream(OutputStream output) {
            super(output);
        }

        @Override public void write(int value) throws IOException {
            out.write(value);
            bytes++;
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            out.write(data, offset, length);
            bytes += length;
        }
    }
}
