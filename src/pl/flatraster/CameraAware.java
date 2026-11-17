package pl.flatraster;

/** An encoder that also wants the camera frame itself (the puppet encoder runs a landmark model on it). Called on the camera thread just
 *  before encode(), with the frame the picture was made from. */
interface CameraAware {
    void cameraFrame(android.media.Image img, int rotation, boolean mirror, long timeMs);
}
