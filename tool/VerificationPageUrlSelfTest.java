import com.red.note.protocol.VerificationPageUrl;
import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

public final class VerificationPageUrlSelfTest {
    public static void main(String[] args) throws Exception {
        String uuid = "challenge*abc&redirectPath=https://invalid.example/";
        String url = VerificationPageUrl.build("https://www.xiaohongshu.com", 461, uuid, "102", "sns_web", "fixture");
        check(VerificationPageUrl.isTrusted(url));
        Map<String, String> query = new HashMap<>();
        for (String part : new URI(url).getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            query.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        check(uuid.equals(query.get("verifyUuid")));
        check("https://www.xiaohongshu.com/explore".equals(query.get("redirectPath")));
        check(VerificationPageUrl.isTrusted(VerificationPageUrl.build("https://www.rednote.com", 471, "id", "124", "", "")));
        check(VerificationPageUrl.build("https://www.xiaohongshu.com", 461, "", "102", "", "").isEmpty());
        check(VerificationPageUrl.build("https://www.xiaohongshu.com", 406, "id", "102", "", "").isEmpty());
        check(VerificationPageUrl.build("https://www.xiaohongshu.com", 461, "id", "301", "", "").isEmpty());
        check(!VerificationPageUrl.isTrusted(url.replace("www.xiaohongshu.com", "www.xiaohongshu.com.evil.example")));
        check(!VerificationPageUrl.isTrusted(url.replace("https://www.", "https://user@www.")));
        check(!VerificationPageUrl.isTrusted("https://www.xiaohongshu.com/web-login/captcha"));
        check(!VerificationPageUrl.isTrusted(url + "&verifyType=124"));
        System.out.println("Verification page URL: 11 cases passed");
    }
    private static void check(boolean valid) {
        if (!valid) throw new AssertionError("Verification URL fixture failed");
    }
}
