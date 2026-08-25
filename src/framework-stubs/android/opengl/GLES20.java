/* artzig stub: GL calls are inert; errors always report success. */
package android.opengl;
public class GLES20 {
    public static final int GL_NO_ERROR = 0;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;
    private static int sNextTex = 1;
    public static void glGenTextures(int n, int[] textures, int offset) {
        for (int i = 0; i < n; i++) textures[offset + i] = sNextTex++;
    }
    public static void glDeleteTextures(int n, int[] textures, int offset) {}
    public static void glBindTexture(int target, int texture) {}
    public static void glTexParameteri(int target, int pname, int param) {}
    public static void glTexParameterf(int target, int pname, float param) {}
    public static int glGetError() { return GL_NO_ERROR; }
    public static String glGetString(int name) { return ""; }
}
