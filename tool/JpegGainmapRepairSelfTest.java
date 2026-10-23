import com.red.note.media.JpegGainmapRepair;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class JpegGainmapRepairSelfTest {
    public static void main(String[] args) throws Exception {
        for (int size = 0; size < 128; size++) {
            assert JpegGainmapRepair.repair(new byte[size]) == null;
        }
        assert JpegGainmapRepair.repair(new byte[]{-1, -40, -1, -39}) == null;
        assert JpegGainmapRepair.repair(new byte[]{-1, -40, -1, -31, -1, -1}) == null;
        if (args.length > 0) {
            byte[] source = Files.readAllBytes(Path.of(args[0]));
            byte[] output = JpegGainmapRepair.repair(source);
            assert output != null && output.length > source.length;
            assert JpegGainmapRepair.repair(output) == null : "must not repair twice";
            assert Arrays.equals(Arrays.copyOfRange(source, 3651887, source.length),
                    Arrays.copyOfRange(output, 3651887 + output.length - source.length, output.length))
                    : "real gain map must remain byte-for-byte unchanged";
            for (int length : new int[]{2, 100, 11580, 3651887, source.length - 1}) {
                assert JpegGainmapRepair.repair(Arrays.copyOf(source, length)) == null;
            }
            byte[] wrongSize = source.clone();
            wrongSize[11643] ^= 127;
            assert JpegGainmapRepair.repair(wrongSize) == null;
        }
        System.out.println("JPEG gain-map repair assertions passed");
    }
}
