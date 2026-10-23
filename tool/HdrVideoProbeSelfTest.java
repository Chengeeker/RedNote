/** Run with app_process and the built APK on the classpath; never installs the APK. */
public final class HdrVideoProbeSelfTest {
    public static void main(String[] args) throws Exception {
        Object probe = Class.forName("com.red.note.HdrVideoProbe").getField("INSTANCE").get(null);
        java.lang.reflect.Method method = probe.getClass().getMethod("probe", String.class, String.class);
        if (args.length > 1 && args[1].equals("reject")) {
            try {
                method.invoke(probe, args[0], "https://www.xiaohongshu.com/");
                throw new AssertionError("Unsafe source was accepted");
            } catch (java.lang.reflect.InvocationTargetException error) {
                if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
                System.out.println("Unsafe video source rejected before network access");
                return;
            }
        }
        Object result = method.invoke(probe, args[0], "https://www.xiaohongshu.com/");
        System.out.println("Video HDR metadata confirmed=" + result);
        boolean expected = args.length < 2 || Boolean.parseBoolean(args[1]);
        if (expected != Boolean.TRUE.equals(result)) {
            throw new AssertionError("Unexpected HDR classification");
        }
    }
}
