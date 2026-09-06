/* artzig stub: no legacy Android cameras are exposed by the desktop host. */
package android.hardware;

public class Camera {
    private Camera() {}

    public static int getNumberOfCameras() { return 0; }

    // Pinned Camera.CameraInfo value layout; full Camera requires cameraserver JNI.
    public static class CameraInfo {
        public static final int CAMERA_FACING_BACK = 0;
        public static final int CAMERA_FACING_FRONT = 1;
        public int facing;
        public int orientation;
        public boolean canDisableShutterSound;
    }
    // Pinned android_hardware_Camera_getCameraInfo rejects every ID when camera count is zero.
    public static void getCameraInfo(int id, CameraInfo info) {
        throw new RuntimeException("Unknown camera ID");
    }

}
