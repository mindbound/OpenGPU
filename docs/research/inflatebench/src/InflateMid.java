import java.util.zip.*;
import java.util.Random;
/** Inflate cost at a realistic 3D-frame deflate ratio (~1.5-2.5x): RGB565 gradient + noise. */
public class InflateMid {
    static long sink;
    public static void main(String[] a) throws Exception {
        Random rnd = new Random(7);
        int[][] dims = {{320,200,2},{640,400,2},{640,400,1}};
        for (int[] d : dims) {
            int w=d[0], h=d[1], bpp=d[2]; byte[] f = new byte[w*h*bpp];
            for (int y=0;y<h;y++) for (int x=0;x<w;x++) {
                int r = (x*31/w) + (rnd.nextInt(3)-1); int g=(y*63/h)+(rnd.nextInt(3)-1); int b=((x+y)*31/(w+h));
                r=Math.max(0,Math.min(31,r)); g=Math.max(0,Math.min(63,g));
                int p = (r<<11)|(g<<5)|b; int i=(y*w+x)*bpp;
                if (bpp==2){ f[i]=(byte)p; f[i+1]=(byte)(p>>8);} else f[i]=(byte)(p&0xff);
            }
            Deflater def = new Deflater(Deflater.BEST_SPEED); def.setInput(f); def.finish();
            byte[] out = new byte[f.length+1024]; int n = def.deflate(out); def.end();
            byte[] z = new byte[n]; System.arraycopy(out,0,z,0,n);
            byte[] dst = new byte[f.length];
            for (int wu=0; wu<3; wu++) loop(z,dst,100);
            double ns = loop(z,dst,400);
            long t0=System.nanoTime(); for(int i=0;i<200;i++){Deflater dd=new Deflater(Deflater.BEST_SPEED); dd.setInput(f); dd.finish(); sink+=dd.deflate(out); dd.end();} double nsDef=(System.nanoTime()-t0)/200.0;
            System.out.printf("%dx%d x%dB: raw %d -> deflated %d (ratio %.2f)  inflate %.3f ms  deflate(BEST_SPEED) %.3f ms%n", w,h,bpp,f.length,n,(double)f.length/n, ns/1e6, nsDef/1e6);
        }
        System.out.println("sink="+sink+" vm="+System.getProperty("java.vm.version"));
    }
    static double loop(byte[] z, byte[] dst, int n) throws Exception {
        long t0=System.nanoTime();
        for (int i=0;i<n;i++){ Inflater inf=new Inflater(); inf.setInput(z); sink+=inf.inflate(dst); inf.end(); }
        return (System.nanoTime()-t0)/(double)n;
    }
}
