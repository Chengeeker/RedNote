import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only media/cache check via app_process; no package installation or Activity. */
public final class HdrImageCacheSelfTest {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        File root = new File(args[1]);
        File cache = new File(root, "cacheimage");
        if (!cache.mkdirs() && !cache.isDirectory()) throw new AssertionError("Cache directory");
        String[] urls = {"https://sns-img-bd.xhscdn.com/cache-fixture-one", "https://sns-img-bd.xhscdn.com/cache-fixture-two"};
        File[] files = new File[2];
        for (int i = 0; i < 2; i++) {
            byte[] digest = MessageDigest.getInstance("MD5").digest(urls[i].getBytes("UTF-8"));
            StringBuilder key = new StringBuilder();
            for (byte value : digest) key.append(String.format("%02x", value));
            files[i] = new File(cache, key.toString());
            Files.copy(new File(args[0]).toPath(), files[i].toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Context context = new ContextWrapper(null) { @Override public File getCacheDir() { return root; } };
        Class<?> messengerClass = Class.forName("io.flutter.plugin.common.BinaryMessenger");
        Object messenger = Proxy.newProxyInstance(messengerClass.getClassLoader(), new Class<?>[]{messengerClass}, (p, m, a) -> null);
        Class<?> channelClass = Class.forName("io.flutter.plugin.common.MethodChannel");
        Object channel = channelClass.getConstructor(messengerClass, String.class).newInstance(messenger, "test");
        Class<?> factoryClass = Class.forName("com.red.note.HdrImageViewFactory");
        Object factory = factoryClass.getConstructor(channelClass).newInstance(channel);
        Class<?> callbackClass = Class.forName("kotlin.jvm.functions.Function1");
        java.lang.reflect.Method probe = factoryClass.getMethod("probeCachedImage", Context.class, String.class, callbackClass);
        java.lang.reflect.Field memory = factoryClass.getDeclaredField("bitmaps");
        memory.setAccessible(true);
        AtomicInteger calls = new AtomicInteger();
        Object[] callback = new Object[1];
        callback[0] = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass}, (p, m, a) -> {
            if (!m.getName().equals("invoke")) return null;
            if (!Boolean.TRUE.equals(a[0])) { System.out.println("HDR cache confirmation failed at " + calls.get()); System.exit(2); }
            int count = calls.incrementAndGet();
            if (count == 2) probe.invoke(factory, context, urls[1], callback[0]);
            if (count == 3) {
                LruCache<?, ?> images = (LruCache<?, ?>) memory.get(factory);
                System.out.println("cacheBytes=" + images.size() + " budget=" + images.maxSize());
                for (Object value : images.snapshot().values()) {
                    Bitmap bitmap = (Bitmap) value;
                    Bitmap gain = bitmap.getGainmap().getGainmapContents();
                    System.out.println("base=" + bitmap.getWidth() + "x" + bitmap.getHeight() + " gain=" + gain.getWidth() + "x" + gain.getHeight());
                }
                files[0].delete();
                probe.invoke(factory, context, urls[0], callback[0]);
            }
            if (count == 4) {
                files[1].delete(); cache.delete(); root.delete();
                System.out.println("Concurrent decode sharing and two-image memory reuse passed");
                System.exit(0);
            }
            return Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        });
        probe.invoke(factory, context, urls[0], callback[0]);
        probe.invoke(factory, context, urls[0], callback[0]);
        new Handler(Looper.getMainLooper()).postDelayed(() -> { System.out.println("Cache test timeout"); System.exit(3); }, 15000);
        Looper.loop();
    }
}
