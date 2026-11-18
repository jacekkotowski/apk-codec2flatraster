package pl.flatraster;

import java.util.ArrayList;
import java.util.List;

/** Plays a Puppet recording as a function of time: frameAt(ms) is the picture to show at that moment. The whole file is decoded first (it is small), so a frame
 *  between two recorded pictures can use both: the points are interpolated (smooth movement at any frame rate, no extra bytes), a new key picture is
 *  cross-faded in over FADE_MS instead of jumping, and the background follows the camera by the shift sent with each picture. Pure Java (the natives
 *  of Exact and Points), so it is tested on a computer. */
final class PuppetPlayback implements java.io.Closeable {
    static final int FADE_MS = 600, FPS = 12;

    /** One recorded picture of a segment (the pictures after one key picture): time, face (30 points) and body (8), in picture pixels, or null; shift in pixels. */
    private static final class Pic { long t; float[] face, body; float sx, sy; }
    /** A key picture and the pictures that follow it. ref*: the first face and body points after the key (what the key picture shows). */
    private static final class Seg { long t; int[] px; final List<Pic> pics = new ArrayList<>(); float[] refFace, refBody; }

    final int w, h;
    private final List<Seg> segs = new ArrayList<>();

    PuppetPlayback(FrvReader r) {
        w = r.width; h = r.height;
        List<FrvReader.Packet> list = new ArrayList<>(r.packets);
        list.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));              // stable: a key picture stays before the points that share its time
        Exact ex = new Exact(w, h); Points pd = new Points(PuppetDraw.SIZES);
        try {
            int[] px = new int[w * h]; Seg cur = null;
            for (FrvReader.Packet p : list) {
                if (p.type == FrvWriter.KEY) {
                    if (!ex.decode(FrvWriter.KEY, p.data, px)) continue;
                    cur = new Seg(); cur.t = p.timeMs; cur.px = px.clone(); segs.add(cur); pd.reset();
                } else if (p.type == FrvWriter.POINTS && cur != null) {
                    int[] present = new int[PuppetDraw.SIZES.length], units = new int[pd.points * 2];
                    if (!pd.decode(p.data, present, units)) continue;
                    Pic c = new Pic(); c.t = p.timeMs;
                    int from = 0;
                    c.face = PuppetDraw.unitsToPixels(units, from, PuppetDraw.SIZES[0], present[0] != 0, w); if (present[0] != 0) from += PuppetDraw.SIZES[0];
                    c.body = PuppetDraw.unitsToPixels(units, from, PuppetDraw.SIZES[1], present[1] != 0, w); if (present[1] != 0) from += PuppetDraw.SIZES[1];
                    if (present[2] != 0) { c.sx = (units[from * 2] - PuppetDraw.CAM_ZERO) * (w / (float) PuppetDraw.UNITS); c.sy = (units[from * 2 + 1] - PuppetDraw.CAM_ZERO) * (w / (float) PuppetDraw.UNITS); }
                    if (cur.refFace == null && c.face != null) cur.refFace = c.face;
                    if (cur.refBody == null && c.body != null) cur.refBody = c.body;
                    cur.pics.add(c);
                }
            }
        } finally { ex.close(); pd.close(); }
    }

    boolean empty() { return segs.isEmpty(); }

    /** The picture at this time, or null before the first key picture. A new array each call. */
    int[] frameAt(long ms) {
        int k = -1;
        for (int i = 0; i < segs.size(); i++) if (segs.get(i).t <= ms) k = i;
        if (k < 0) return null;
        int[] now = render(segs.get(k), ms);
        long into = ms - segs.get(k).t;
        if (k > 0 && into < FADE_MS) {                                        // the previous key picture goes on moving while the new one fades in
            int[] old = render(segs.get(k - 1), ms);
            int a = (int) (256 * into / FADE_MS);
            for (int i = 0; i < now.length; i++) now[i] = mix(old[i], now[i], a);
        }
        return now;
    }

    private int[] render(Seg s, long ms) {
        Pic a = null, b = null;
        for (Pic p : s.pics) { if (p.t <= ms) a = p; else { b = p; break; } }
        if (a == null) a = b;
        if (a == null) return PuppetDraw.draw(s.px, w, h, null, null);
        float[] face = a.face, body = a.body; float sx = a.sx, sy = a.sy;
        if (b != null && b != a && b.t > a.t) {                               // between two recorded pictures: halfway by time
            float f = (float) (ms - a.t) / (b.t - a.t);
            face = lerp(a.face, b.face, f); body = lerp(a.body, b.body, f);
            sx = a.sx + (b.sx - a.sx) * f; sy = a.sy + (b.sy - a.sy) * f;
        }
        int[] base = PuppetDraw.shift(s.px, w, h, sx, sy);                    // the background follows the camera; the person is moved by the points
        float[] rf = shifted(s.refFace, sx, sy), rb = shifted(s.refBody, sx, sy);
        float[] fs = shifted(PuppetDraw.lipsStronger(s.refFace, face, PuppetDraw.lipGain), 0, 0);
        int[] moved = PuppetDraw.warp(base, w, h, rf, rb, fs, body);
        return PuppetDraw.draw(moved, w, h, fs, body);
    }

    private static float[] lerp(float[] a, float[] b, float f) {
        if (a == null) return b;
        if (b == null || a.length != b.length) return a;
        float[] o = new float[a.length];
        for (int i = 0; i < o.length; i++) o[i] = a[i] + (b[i] - a[i]) * f;
        return o;
    }

    private static float[] shifted(float[] p, float sx, float sy) {
        if (p == null || (sx == 0 && sy == 0)) return p;
        float[] o = p.clone();
        for (int i = 0; i < o.length; i += 2) { o[i] += sx; o[i + 1] += sy; }
        return o;
    }

    static int mix(int p, int q, int a) {          // a/256 of q
        int r = ((p >> 16 & 255) * (256 - a) + (q >> 16 & 255) * a) >> 8, g = ((p >> 8 & 255) * (256 - a) + (q >> 8 & 255) * a) >> 8, b = ((p & 255) * (256 - a) + (q & 255) * a) >> 8;
        return 0xff000000 | r << 16 | g << 8 | b;
    }

    @Override public void close() { }
}
