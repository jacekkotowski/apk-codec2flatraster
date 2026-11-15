package pl.flatraster;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import java.util.List;
import pl.dyktafon.SelfTest;

/**
 * Self-test of the phone's video encoders (stage 0 of ROADMAP.md): for AV1, HEVC and H.264, which encoders exist (software or in the camera chip),
 * the lowest bit rate each accepts, and what the app's own VideoEncoder really produces from the made-up posterized pictures at 2 pictures
 * a second: bytes, real bit rate against the one asked for, size of the full pictures, and time. The real bit rate is what decides
 * how long a recording takes on a radio link.
 */
final class VideoTest {
    /** Asked bit rates in kbit/s: the app's default, and the lowest it offers. */
    private static final int[] KBPS = {5, 2};

    static void run(SelfTest.Log log, List<byte[]> frames) {
        final double fps = 2.0; final int w = PictureTest.OUT_W, h = PictureTest.OUT_H;
        for (int c = 0; c < FrvWriter.MIME.length; c++) {
            if (log.cancelled()) return;
            String mime = FrvWriter.MIME[c]; StringBuilder list = new StringBuilder();
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(mime)) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                        list.append("\n    ").append(ci.getName()).append(ci.isSoftwareOnly() ? " (software)" : ci.isHardwareAccelerated() ? " (hardware)" : "")
                            .append(vc.isSizeSupported(w, h) ? ", " + w + "x" + h + " ok" : ", " + w + "x" + h + " NOT supported")
                            .append(", bit rate ").append(vc.getBitrateRange().getLower() / 1000.0).append(" to ").append(vc.getBitrateRange().getUpper() / 1000).append(" kbit/s");
                    } catch (RuntimeException e) { list.append("\n    ").append(ci.getName()).append(": ").append(e); }
                }
            }
            log.line(FrvWriter.CODEC_NAME[c] + " encoders:" + (list.length() == 0 ? " none" : list));
            if (list.length() == 0) continue;
            for (int kbps : KBPS) {
                long[] bytes = {0, 0, 0}; int[] count = {0, 0}; // video bytes, key picture bytes, config bytes; picture count, key count
                long t0 = System.nanoTime();
                try {
                    VideoEncoder e = new VideoEncoder(c, w, h, fps, kbps * 1000, 10, (type, ms, d) -> {
                        if (type == FrvWriter.CONFIG) { bytes[2] += d.length; return; }
                        bytes[0] += d.length; count[0]++;
                        if (type == FrvWriter.KEY) { bytes[1] += d.length; count[1]++; }
                    });
                    try { for (int i = 0; i < frames.size() && !log.cancelled(); i++) e.encode(frames.get(i), Math.round(i * 1000 / fps)); }
                    finally { e.close(); }
                    double secs = frames.size() / fps, took = (System.nanoTime() - t0) / 1e9;
                    log.line(SelfTest.f("  %s asked %d kbit/s, encoder set to %.2f: %d pictures out of %d, %d bytes = %.2f kbit/s really; %d full pictures averaging %d bytes; config %d bytes; %.1f s to encode %.0f s",
                            e.name, kbps, e.bitrate / 1000.0, count[0], frames.size(), bytes[0], bytes[0] * 8 / secs / 1000, count[1], count[1] == 0 ? 0 : bytes[1] / count[1], bytes[2], took, secs));
                } catch (Throwable e) { log.line("  asked " + kbps + " kbit/s: FAILED, " + e.getMessage()); }
            }
        }
    }
}
