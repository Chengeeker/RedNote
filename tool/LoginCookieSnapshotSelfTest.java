import com.red.note.protocol.LoginCookieSnapshot;

public final class LoginCookieSnapshotSelfTest {
    public static void main(String[] args) {
        String fresh = "a1=device; web_session=fresh";
        String stale = "a1=device; web_session=stale";
        check(LoginCookieSnapshot.select("device", fresh, stale).equals(fresh));
        check(LoginCookieSnapshot.select("device", "a1=other; web_session=old", fresh).equals(fresh));
        check(LoginCookieSnapshot.select("device", "a1=device", fresh).equals(fresh));
        check(LoginCookieSnapshot.select("device", "a1=device; web_session=old; web_session=new").isEmpty());
        check(LoginCookieSnapshot.select("device", "a1=old; a1=device; web_session=new").isEmpty());
        check(LoginCookieSnapshot.select("", fresh).isEmpty());
        check(LoginCookieSnapshot.session("a1=device; web_session=x=y==").equals("x=y=="));
        System.out.println("Login cookie snapshot: 7 cases passed");
    }
    private static void check(boolean valid) {
        if (!valid) throw new AssertionError("Login cookie fixture failed");
    }
}
