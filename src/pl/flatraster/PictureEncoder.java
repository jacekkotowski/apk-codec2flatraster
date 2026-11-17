package pl.flatraster;

/** What the recorder needs of a picture encoder: the phone's video encoder (VideoEncoder) or the exact coder (ExactEncoder). */
interface PictureEncoder {
    /** Where coded pictures go: type FrvWriter.KEY or DELTA, time in ms, the packet payload. */
    interface Sink { void video(int type, long timeMs, byte[] data) throws java.io.IOException; }
    int codec();            // FrvWriter codec number
    String describe();      // for the status line
    void encode(byte[] i420, long timeMs) throws java.io.IOException;
    void requestKeyFrame();
    void close();
}
