package pl.flatraster;

import java.io.FileOutputStream;

/** Stage 6a (ROADMAP.md): writes the camera frames of the self-test scene (PictureTest.scene, the same noise seed as PictureTest.filterSpeed)
 *  as raw I420, 640 x 480, so tools/coder/dump.c can run the real filter on them. Usage: SceneDump out.i420 [frames] */
public class SceneDump {
    public static void main(String[] a) throws Exception {
        int frames = a.length > 1 ? Integer.parseInt(a[1]) : 60;
        java.util.Random noise = new java.util.Random(5);
        try (FileOutputStream out = new FileOutputStream(a[0])) {
            for (int t = 0; t < frames; t++) out.write(PictureTest.scene(t, noise));
        }
    }
}
