import com.red.note.media.MediaImageFormat;
import java.nio.charset.StandardCharsets;

public final class MediaImageFormatSelfTest {
    public static void main(String[] args) {
        check("\u00ff\u00d8\u00ff\u00e1gainmap", "image/jpeg");
        check("\u0089PNG\r\n\u001a\n", "image/png");
        check("GIF89a", "image/gif");
        check("RIFF1234WEBP", "image/webp");
        check("1234ftypavif", "image/avif");
        check("1234ftypheic", "image/heic");
        check("1234ftypmif1", "image/heif");
        check("1234ftypisom", "");
        check("<html>error</html>", "");
        check("", "");
        byte[] truncated = "RIFF1234WEBP".getBytes(StandardCharsets.ISO_8859_1);
        if (!MediaImageFormat.mime(truncated, 8).isEmpty()) throw new AssertionError("truncated header");
        if (!MediaImageFormat.mime(truncated, 99).isEmpty()) throw new AssertionError("invalid length");
        System.out.println("Media image format: 12 cases passed");
    }
    private static void check(String bytes, String expected) {
        byte[] data = bytes.getBytes(StandardCharsets.ISO_8859_1);
        if (!MediaImageFormat.mime(data, data.length).equals(expected)) throw new AssertionError(expected);
    }
}
