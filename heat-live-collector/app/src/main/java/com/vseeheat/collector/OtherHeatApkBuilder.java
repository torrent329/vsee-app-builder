package com.vseeheat.collector;

import android.content.Context;
import android.net.Uri;

import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds one of the five extra APKs from an audited, sanitized template. */
final class OtherHeatApkBuilder {
    private static final int MAX_FILE = 64 * 1024 * 1024;
    private static final long MAX_EXPANDED = 128L * 1024 * 1024;

    private OtherHeatApkBuilder() {}

    static Uri build(Context context, HeatAppSpec.Spec spec, JSONObject profile,
                     OwnerSigningKeys.Material owner, String relativePath) throws Exception {
        if (!OtherHeatGate.inspectOne(context, spec).supported()) {
            throw new HeatLiveApkBuilder.BuildFailure("ORIGINAL_CHANGED");
        }
        File template = File.createTempFile("heat-extra-template-", ".apk",
                context.getCacheDir());
        File unsigned = File.createTempFile("heat-extra-unsigned-", ".apk",
                context.getCacheDir());
        File signed = File.createTempFile("heat-extra-signed-", ".apk",
                context.getCacheDir());
        try {
            try (InputStream input = CompatibilityUpdates.openTemplate(context, spec);
                 OutputStream output = new FileOutputStream(template)) {
                HeatLiveApkBuilder.copy(input, output, MAX_FILE);
            }
            if (!spec.templateSha256.equals(HeatLiveApkBuilder.sha256(template))) {
                throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
            }
            byte[] pin = HeatLiveApkBuilder.ownerPin(owner.certificate);
            repackage(template, unsigned, spec,
                    profile.toString().getBytes(StandardCharsets.UTF_8), pin);
            ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("owner",
                    owner.privateKey, Collections.singletonList(owner.certificate)).build();
            new ApkSigner.Builder(Collections.singletonList(signer))
                    .setInputApk(unsigned)
                    .setOutputApk(signed)
                    .setMinSdkVersion(spec.minSdk)
                    .setV1SigningEnabled(spec.minSdk < 24)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setV4SigningEnabled(false)
                    .setOtherSignersSignaturesPreserved(false)
                    .build().sign();
            ApkVerifier.Result verified = new ApkVerifier.Builder(signed)
                    .setMinCheckedPlatformVersion(spec.minSdk).build().verify();
            if (!verified.isVerified() || verified.getSignerCertificates().size() != 1
                    || !MessageDigest.isEqual(owner.certificate.getEncoded(),
                    verified.getSignerCertificates().get(0).getEncoded())) {
                throw new HeatLiveApkBuilder.BuildFailure("SIGNATURE_VERIFY_FAILED");
            }
            if (!OtherHeatGate.inspectOne(context, spec).supported()) {
                throw new HeatLiveApkBuilder.BuildFailure("ORIGINAL_CHANGED");
            }
            if ("com.google.heatlive".equals(spec.originalPackage)
                    && !HeatLiveGate.inspect(context).supported()) {
                throw new HeatLiveApkBuilder.BuildFailure("CHECK_FAILED");
            }
            return HeatLiveApkBuilder.exportDownloads(context, signed,
                    spec.outputName, relativePath);
        } finally {
            template.delete();
            unsigned.delete();
            signed.delete();
        }
    }

    private static void repackage(File template, File unsigned,
                                  HeatAppSpec.Spec spec, byte[] profile,
                                  byte[] newPin) throws Exception {
        try (ZipFile source = new ZipFile(template);
             HeatLiveApkBuilder.CountingStream count =
                     new HeatLiveApkBuilder.CountingStream(new FileOutputStream(unsigned));
             ZipOutputStream target = new ZipOutputStream(count, StandardCharsets.UTF_8)) {
            if (source.size() != spec.templateEntries) {
                throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
            }
            Set<String> names = new HashSet<>();
            int changed = 0;
            long expanded = 0;
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry original = entries.nextElement();
                String name = original.getName();
                if (!names.add(name) || HeatLiveApkBuilder.isSignatureEntry(name)) {
                    throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
                }
                byte[] bytes;
                try (InputStream input = source.getInputStream(original)) {
                    bytes = HeatLiveApkBuilder.readBounded(input, MAX_FILE);
                }
                expanded += bytes.length;
                if (expanded > MAX_EXPANDED) {
                    throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
                }
                if (HeatAppSpec.PROFILE.equals(name)) {
                    if (!HeatAppSpec.PLACEHOLDER_SHA256.equals(
                            HeatLiveApkBuilder.sha256(bytes))) {
                        throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
                    }
                    bytes = profile;
                    changed++;
                } else if (HeatAppSpec.LIB32.equals(name)
                        || HeatAppSpec.LIB64.equals(name)) {
                    boolean is32 = HeatAppSpec.LIB32.equals(name);
                    String expected = is32 ? spec.lib32Sha256 : spec.lib64Sha256;
                    int offset = is32 ? spec.pin32Offset : spec.pin64Offset;
                    if (!expected.equals(HeatLiveApkBuilder.sha256(bytes))
                            || offset + 32 > bytes.length
                            || !Arrays.equals(Arrays.copyOfRange(bytes, offset, offset + 32),
                            "00000000000000000000000000000000"
                                    .getBytes(StandardCharsets.US_ASCII))) {
                        throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
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
                    throw new HeatLiveApkBuilder.BuildFailure("ZIP_ALIGNMENT_FAILED");
                }
                target.write(bytes);
                target.closeEntry();
            }
            if (changed != 3 || names.size() != spec.templateEntries) {
                throw new HeatLiveApkBuilder.BuildFailure("TEMPLATE_MISMATCH");
            }
        }
    }
}
