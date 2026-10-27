package com.vseeheat.collector;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Measures the runtime ZIP header before aligning an uncompressed APK entry. */
final class ZipAlignment {
    private ZipAlignment() {}

    static byte[] extraForStored(String name, long time, long outputOffset)
            throws IOException {
        for (int padding = 0; padding < 4; padding++) {
            byte[] extra = new byte[4 + padding];
            extra[0] = (byte) 0xd9;
            extra[1] = 0x35;
            extra[2] = (byte) padding;

            ZipEntry probeEntry = new ZipEntry(name);
            if (time >= 0) probeEntry.setTime(time);
            probeEntry.setMethod(ZipEntry.STORED);
            probeEntry.setSize(0);
            probeEntry.setCompressedSize(0);
            probeEntry.setCrc(0);
            probeEntry.setExtra(extra);
            HeatLiveApkBuilder.CountingStream count = new HeatLiveApkBuilder.CountingStream(
                    new ByteArrayOutputStream());
            try (ZipOutputStream probe = new ZipOutputStream(count,
                    StandardCharsets.UTF_8)) {
                probe.putNextEntry(probeEntry);
                if ((outputOffset + count.bytes) % 4 == 0) return extra;
                probe.closeEntry();
            }
        }
        throw new IOException("Could not align ZIP entry header");
    }
}
