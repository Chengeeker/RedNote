package com.red.note.protocol;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Select a coherent browser login, without mixing stale per-host credentials. */
public final class LoginCookieSnapshot {
    private LoginCookieSnapshot() {}

    private static Map<String, String> parse(String header) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String piece : header.split(";")) {
            int index = piece.indexOf('=');
            if (index <= 0) continue;
            String name = piece.substring(0, index).trim();
            String value = piece.substring(index + 1).trim();
            String old = values.put(name, value);
            if ((name.equals("a1") || name.equals("web_session")) && old != null && !old.equals(value)) {
                return Collections.emptyMap();
            }
        }
        return values;
    }

    public static String select(String a1, String... headers) {
        if (a1.isEmpty()) return "";
        for (String header : headers) {
            Map<String, String> values = parse(header);
            if (a1.equals(values.get("a1")) && !values.getOrDefault("web_session", "").isEmpty()) return header;
        }
        return "";
    }

    public static String session(String header) {
        return parse(header).getOrDefault("web_session", "");
    }
}
