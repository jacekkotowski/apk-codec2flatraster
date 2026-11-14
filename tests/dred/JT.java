package pl.dyktafon;

import java.io.*;
import java.nio.*;
import java.nio.file.*;

/** The app's JNI library against the command-line test program (PLAN.md stage 9, part B, step 4).
 *  java -Djava.library.path=tests/dred/out -cp tests/dred/out pl.dyktafon.JT q in16k.raw out.dred out.raw
 *  Codes in.raw with DredLib.Coder at level q into a .dred file and plays it back with DredLib.Player into out.raw.
 *  Compare with: tests/dred/dredtest enc2 q 50 in.raw ref.blocks; tests/dred/dredtest dec2 50 ref.blocks ref.raw */
public class JT {
    public static void main(String[] a) throws Exception {
        int q = Integer.parseInt(a[0]);
        DataInputStream s = new DataInputStream(new BufferedInputStream(new FileInputStream(a[1]), 65536)); long left = new File(a[1]).length() / 2;
        long t0 = System.nanoTime(); int blocks = 0;
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(a[2])); DredLib.Coder c = new DredLib.Coder(q)) {
            o.write(Dred.header(q));
            short[] f = new short[Dred.FRAME];
            while (left > 0) {
                java.util.Arrays.fill(f, (short) 0); int k = (int) Math.min(f.length, left); left -= k;
                for (int i = 0; i < k; i++) f[i] = Short.reverseBytes(s.readShort());
                int n = c.frame(f); if (n >= 0) { Dred.writeBlock(o, c.buf, n); blocks++; }
            }
            c.finish((x, n) -> Dred.writeBlock(o, x, n));
        }
        long t1 = System.nanoTime();
        Dred.Index x = Dred.Index.of(new File(a[2]));
        try (RandomAccessFile in = new RandomAccessFile(a[2], "r"); OutputStream o = new BufferedOutputStream(new FileOutputStream(a[3])); DredLib.Player p = new DredLib.Player()) {
            byte[] buf = new byte[Dred.MAX_BLOCK]; ByteBuffer ob = ByteBuffer.allocate(Dred.BLOCK_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < x.blocks; i++) { int m = p.block(buf, x.read(in, i, buf)); ob.clear(); for (int k = 0; k < m; k++) ob.putShort(p.pcm[k]); o.write(ob.array(), 0, m * 2); }
        }
        long t2 = System.nanoTime();
        System.out.printf("q %d blocks %d seconds %.0f bytes %d code_ms %.0f play_ms %.0f%n", x.q, x.blocks, x.seconds(), new File(a[2]).length(), (t1 - t0) / 1e6, (t2 - t1) / 1e6);
    }
}
