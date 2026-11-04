import com.red.note.protocol.ProfilePageUrl;
import java.util.Map;

public final class ProfilePageUrlSelfTest {
    private static int checks;

    public static void main(String[] args) {
        Map<String, String> profile = ProfilePageUrl.parse("https://www.xiaohongshu.com/user/profile/peer_123?xsec_token=a%2Bb%2F%3D%3D&xsec_source=pc_im&tracking=ignored");
        check(profile != null && "peer_123".equals(profile.get("id")));
        check("a+b/==".equals(profile.get("token")));
        check("pc_im".equals(profile.get("source")));
        check(ProfilePageUrl.parse("https://www.rednote.com/user/profile/peer-1/") != null);
        check(ProfilePageUrl.parse("https://rednote.com:443/user/profile/peer") != null);
        check(ProfilePageUrl.parse("https://xiaohongshu.com/user/profile/peer") != null);
        for (String url : new String[] {
                "http://www.xiaohongshu.com/user/profile/peer",
                "https://www.xiaohongshu.com.evil.example/user/profile/peer",
                "https://evil.example/user/profile/peer",
                "https://evil@www.xiaohongshu.com/user/profile/peer",
                "https://www.xiaohongshu.com:444/user/profile/peer",
                "https://www.xiaohongshu.com/chat/peer",
                "https://www.xiaohongshu.com/user/profile/",
                "https://www.xiaohongshu.com/user/profile/a/b",
                "https://www.xiaohongshu.com/user/profile/%2e%2e",
                "https://www.xiaohongshu.com/user/profile/peer?xsec_token=a&xsec_token=b",
                "https://www.xiaohongshu.com/user/profile/peer?xsec_source=a&xsec_source=b",
                "https://www.xiaohongshu.com/user/profile/peer?xsec_source=%0A",
                "https://www.xiaohongshu.com/user/profile/peer?xsec_token=%XX",
                "https://www.xiaohongshu.com/user/profile/" + "a".repeat(129),
                "https://www.xiaohongshu.com/user/profile/peer?xsec_token=" + "a".repeat(4097),
                "https://www.xiaohongshu.com/user/profile/peer?tracking=" + "a".repeat(8192),
        }) check(ProfilePageUrl.parse(url) == null);
        check(ProfilePageUrl.parse(null) == null);
        System.out.println("Profile page URL: " + checks + " checks passed");
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Profile URL check " + checks + " failed");
    }
}
