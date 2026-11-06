package com.red.note.media;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Repairs stale MPF offsets without changing pixels or inventing gain-map parameters. */
public final class JpegGainmapRepair {
    private static final byte[] XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private JpegGainmapRepair() {}

    public static byte[] repair(byte[] data) {
        // shortcut: bound the extra full-file copy; larger images use the system decoder only.
        if (data.length > 16 * 1024 * 1024) return null;
        try {
            int firstEnd = jpegEnd(data, 0);
            if (firstEnd < 0 || jpegEnd(data, firstEnd) != data.length) return null;
            int mpf = segment(data, 0, 0xe2, new byte[]{'M', 'P', 'F', 0});
            int xmp = segment(data, firstEnd, 0xe1, XMP);
            if (mpf < 0 || xmp < 0 || segment(data, 0, 0xe1, XMP) >= 0) return null;
            int xmpLength = u16(data, xmp + 2) + 2;
            String metadata = new String(data, xmp + 4 + XMP.length,
                    xmpLength - 4 - XMP.length, StandardCharsets.UTF_8);
            if (!metadata.contains("http://ns.adobe.com/hdr-gain-map/1.0/")) return null;
            int tiff = mpf + 8;
            int mpfEnd = mpf + 2 + u16(data, mpf + 2);
            boolean little = data[tiff] == 'I' && data[tiff + 1] == 'I';
            if (!little && !(data[tiff] == 'M' && data[tiff + 1] == 'M')) return null;
            ByteBuffer buffer = ByteBuffer.wrap(data).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (buffer.getShort(tiff + 2) != 42) return null;
            long directory = tiff + Integer.toUnsignedLong(buffer.getInt(tiff + 4));
            if (directory < tiff || directory + 2 > mpfEnd) return null;
            int count = Short.toUnsignedInt(buffer.getShort((int) directory));
            if (count > 32 || directory + 2 + count * 12L > mpfEnd) return null;
            for (int i = 0; i < count; i++) {
                int entry = (int) directory + 2 + i * 12;
                if (Short.toUnsignedInt(buffer.getShort(entry)) != 0xb002) continue;
                if (buffer.getShort(entry + 2) != 7 || buffer.getInt(entry + 4) != 32) return null;
                long tableLong = tiff + Integer.toUnsignedLong(buffer.getInt(entry + 8));
                if (tableLong < tiff || tableLong + 32 > mpfEnd) return null;
                int table = (int) tableLong;
                if (buffer.getInt(table + 8) != 0 ||
                        Integer.toUnsignedLong(buffer.getInt(table + 20)) != data.length - firstEnd) return null;
                byte[] repaired = new byte[data.length + xmpLength];
                System.arraycopy(data, 0, repaired, 0, 2);
                System.arraycopy(data, xmp, repaired, 2, xmpLength);
                System.arraycopy(data, 2, repaired, 2 + xmpLength, data.length - 2);
                ByteBuffer output = ByteBuffer.wrap(repaired).order(buffer.order());
                output.putInt(table + xmpLength + 4, firstEnd + xmpLength);
                output.putInt(table + xmpLength + 24, firstEnd - tiff);
                return repaired;
            }
        } catch (IndexOutOfBoundsException | IllegalArgumentException ignored) {
            // Malformed containers keep their ordinary SDR decoding path.
        }
        return null;
    }

    private static int u16(byte[] data, int offset) {
        return ((data[offset] & 255) << 8) | (data[offset + 1] & 255);
    }

    private static boolean starts(byte[] data, int offset, byte[] signature) {
        if (offset < 0 || offset + signature.length > data.length) return false;
        for (int i = 0; i < signature.length; i++) if (data[offset + i] != signature[i]) return false;
        return true;
    }

    private static int segment(byte[] data, int start, int wanted, byte[] signature) {
        for (int p = start + 2; p + 4 <= data.length;) {
            if ((data[p] & 255) != 255) return -1;
            int marker = data[p + 1] & 255;
            if (marker == 0xda || marker == 0xd9) return -1;
            int length = u16(data, p + 2);
            if (length < 2 || p + 2L + length > data.length) return -1;
            if (marker == wanted && length >= 2 + signature.length && starts(data, p + 4, signature)) return p;
            p += 2 + length;
        }
        return -1;
    }

    private static int jpegEnd(byte[] data, int start) {
        if (!starts(data, start, new byte[]{(byte) 255, (byte) 216})) return -1;
        boolean entropy = false;
        for (int p = start + 2; p + 1 < data.length;) {
            if ((data[p++] & 255) != 255) {
                if (entropy) continue;
                return -1;
            }
            while (p < data.length && (data[p] & 255) == 255) p++;
            if (p >= data.length) return -1;
            int marker = data[p++] & 255;
            if (entropy && (marker == 0 || (marker >= 0xd0 && marker <= 0xd7))) continue;
            if (marker == 0xd9) return p;
            if (marker == 0 || marker == 0xd8 || p + 2 > data.length) return -1;
            int length = u16(data, p);
            if (length < 2 || p + (long) length > data.length) return -1;
            entropy = marker == 0xda;
            p += length;
        }
        return -1;
    }
}
