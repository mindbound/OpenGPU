import java.util.zip.*;
import java.util.Random;

/** CRC32 and Inflater cost for OpenGPU-sized frames. Run on JRE 8 and JDK 25. */
public class CrcInflateBench {
    static long sink;
    public static void main(String[] a) throws Exception {
        int[] sizes = { 64_000, 128_000, 512_000, 1_024_000 }; // T2 idx8, T2 RGB565, T3 RGB565, T3 ARGB
        Random rnd = new Random(42);
        for (int size : sizes) {
            byte[] noisy = new byte[size]; rnd.nextBytes(noisy);
            byte[] ui = new byte[size]; for (int i = 0; i < size; i++) ui[i] = (byte) ((i / 977) & 7); // low-entropy "UI"
            // warm-up
            for (int w = 0; w < 3; w++) { crcLoop(noisy, 200); crcLoop(ui, 200); }
            double nsCrc = crcLoop(noisy, 2000);
            // deflate once at BEST_SPEED, then time inflate
            byte[] zNoisy = deflate(noisy); byte[] zUi = deflate(ui);
            for (int w = 0; w < 3; w++) { inflateLoop(zNoisy, size, 50); inflateLoop(zUi, size, 50); }
            double nsInfN = inflateLoop(zNoisy, size, 300);
            double nsInfU = inflateLoop(zUi, size, 300);
            // java.util.zip.Adler32 as a cheaper alternative
            double nsAdler = adlerLoop(noisy, 2000);
            System.out.printf("size=%7d B  CRC32 %.3f ms (%.1f GB/s)  Adler32 %.3f ms  inflate noisy(%d B) %.3f ms  inflate ui(%d B) %.3f ms%n",
                size, nsCrc / 1e6, size / nsCrc, nsAdler / 1e6, zNoisy.length, nsInfN / 1e6, zUi.length, nsInfU / 1e6);
        }
        System.out.println("sink=" + sink + " vm=" + System.getProperty("java.vm.version") + " arch=" + System.getProperty("os.arch"));
    }
    static double crcLoop(byte[] b, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { CRC32 c = new CRC32(); c.update(b, 0, b.length); sink += c.getValue(); }
        return (System.nanoTime() - t0) / (double) n;
    }
    static double adlerLoop(byte[] b, int n) {
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { Adler32 c = new Adler32(); c.update(b, 0, b.length); sink += c.getValue(); }
        return (System.nanoTime() - t0) / (double) n;
    }
    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(in); d.finish();
        byte[] out = new byte[in.length + 1024];
        int n = d.deflate(out); d.end();
        byte[] r = new byte[n]; System.arraycopy(out, 0, r, 0, n); return r;
    }
    static double inflateLoop(byte[] z, int size, int n) throws Exception {
        byte[] out = new byte[size];
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) { Inflater inf = new Inflater(); inf.setInput(z); int k = inf.inflate(out); inf.end(); sink += k; }
        return (System.nanoTime() - t0) / (double) n;
    }
}
