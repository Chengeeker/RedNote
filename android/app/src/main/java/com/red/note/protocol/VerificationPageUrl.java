package com.red.note.protocol;

import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

public final class VerificationPageUrl {
    private VerificationPageUrl() {}

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (UnsupportedEncodingException e) { throw new IllegalStateException(e); }
    }

    public static String build(String origin, int status, String uuid, String type, String biz, String message) {
        if ((!origin.equals("https://www.xiaohongshu.com") && !origin.equals("https://www.rednote.com")) ||
                (status != 461 && status != 471) || uuid.isEmpty() || uuid.length() > 512 ||
                !type.matches("[0-9]{1,6}") || type.equals("301") || type.equals("302")) return "";
        return origin + "/web-login/captcha?redirectPath=" + encode(origin + "/explore") +
                "&callFrom=web&appId=xhs-pc-web&verifyUuid=" + encode(uuid) +
                "&verifyType=" + encode(type) + "&verifyBiz=" + status +
                (biz.isEmpty() || biz.length() > 128 ? "" : "&biz=" + encode(biz)) +
                (message.isEmpty() || message.length() > 1024 ? "" : "&verifyMsg=" + encode(message));
    }

    public static boolean isTrusted(String url) {
        try {
            URI value = new URI(url);
            boolean page = value.getUserInfo() == null && value.getPort() == -1 && value.getFragment() == null && "https".equals(value.getScheme()) &&
                    ("www.xiaohongshu.com".equals(value.getHost()) || "www.rednote.com".equals(value.getHost())) &&
                    "/web-login/captcha".equals(value.getPath());
            if (!page || value.getRawQuery() == null) return false;
            Map<String, String> query = new HashMap<>();
            for (String part : value.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                if (pair.length != 2) return false;
                String name = URLDecoder.decode(pair[0], "UTF-8");
                if (query.put(name, URLDecoder.decode(pair[1], "UTF-8")) != null) return false;
            }
            String origin = "https://" + value.getHost();
            String rebuilt = build(origin, Integer.parseInt(query.getOrDefault("verifyBiz", "0")),
                    query.getOrDefault("verifyUuid", ""), query.getOrDefault("verifyType", ""), "", "");
            return !rebuilt.isEmpty() && (origin + "/explore").equals(query.get("redirectPath"));
        } catch (Exception ignored) { return false; }
    }
}
