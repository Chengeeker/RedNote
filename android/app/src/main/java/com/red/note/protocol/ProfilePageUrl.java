package com.red.note.protocol;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProfilePageUrl {
    private static final Pattern PATH = Pattern.compile("/user/profile/([A-Za-z0-9_-]{1,128})/?");

    private ProfilePageUrl() {}

    public static Map<String, String> parse(String url) {
        try {
            if (url == null || url.length() > 8192) return null;
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null ||
                    (uri.getPort() != -1 && uri.getPort() != 443) ||
                    !("www.xiaohongshu.com".equals(host) || "xiaohongshu.com".equals(host) ||
                      "www.rednote.com".equals(host) || "rednote.com".equals(host))) return null;
            Matcher path = PATH.matcher(uri.getPath());
            if (!path.matches()) return null;
            Map<String, String> result = new HashMap<>();
            result.put("id", path.group(1));
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&")) {
                    String[] pair = part.split("=", 2);
                    String key = URLDecoder.decode(pair[0], "UTF-8");
                    if (!key.equals("xsec_token") && !key.equals("xsec_source")) continue;
                    String value = pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "";
                    if (key.equals("xsec_source") && !value.isEmpty() && !value.matches("[A-Za-z0-9_-]{1,128}")) return null;
                    if (value.length() > 4096 || result.put(key.equals("xsec_token") ? "token" : "source", value) != null) return null;
                }
            }
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }
}
