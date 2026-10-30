package com.red.note.media;

import java.nio.charset.StandardCharsets;

/** Detect the actual image container when an original is served as binary data. */
public final class MediaImageFormat {
    private MediaImageFormat() {}

    private static boolean at(byte[] data, int length, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.ISO_8859_1);
        if (offset + expected.length > length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) return false;
        }
        return true;
    }

    public static String mime(byte[] data, int length) {
        if (length < 0 || length > data.length) return "";
        if (at(data, length, 0, "\u00ff\u00d8\u00ff")) return "image/jpeg";
        if (at(data, length, 0, "\u0089PNG\r\n\u001a\n")) return "image/png";
        if (at(data, length, 0, "GIF87a") || at(data, length, 0, "GIF89a")) return "image/gif";
        if (at(data, length, 0, "RIFF") && at(data, length, 8, "WEBP")) return "image/webp";
        if (at(data, length, 4, "ftyp")) {
            if (at(data, length, 8, "avif") || at(data, length, 8, "avis")) return "image/avif";
            if (at(data, length, 8, "heic") || at(data, length, 8, "heix")) return "image/heic";
            if (at(data, length, 8, "mif1") || at(data, length, 8, "msf1")) return "image/heif";
        }
        return "";
    }
}
