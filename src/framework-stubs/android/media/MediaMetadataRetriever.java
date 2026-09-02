/* artzig stub of AOSP android.media.MediaMetadataRetriever: the real class is
 * libmedia_jni (stagefright metadata extraction), absent on this host. Keys are
 * the AOSP values; every extraction answers "no metadata" (ExifInterface then
 * treats HEIF/AVIF input as carrying no EXIF, the AOSP path for an extractor
 * failure). */
package android.media;
public class MediaMetadataRetriever implements AutoCloseable {
    public static final int METADATA_KEY_HAS_VIDEO = 17;
    public static final int METADATA_KEY_VIDEO_WIDTH = 18;
    public static final int METADATA_KEY_VIDEO_HEIGHT = 19;
    public static final int METADATA_KEY_VIDEO_ROTATION = 24;
    public static final int METADATA_KEY_HAS_IMAGE = 26;
    public static final int METADATA_KEY_IMAGE_WIDTH = 29;
    public static final int METADATA_KEY_IMAGE_HEIGHT = 30;
    public static final int METADATA_KEY_IMAGE_ROTATION = 31;
    public static final int METADATA_KEY_EXIF_OFFSET = 33;
    public static final int METADATA_KEY_EXIF_LENGTH = 34;
    public static final int METADATA_KEY_XMP_OFFSET = 41;
    public static final int METADATA_KEY_XMP_LENGTH = 42;
    public MediaMetadataRetriever() {}
    public void setDataSource(String path) throws IllegalArgumentException {}
    public void setDataSource(java.io.FileDescriptor fd) throws IllegalArgumentException {}
    public void setDataSource(java.io.FileDescriptor fd, long offset, long length) throws IllegalArgumentException {}
    public void setDataSource(android.content.Context context, android.net.Uri uri) throws IllegalArgumentException, SecurityException {}
    public void setDataSource(MediaDataSource dataSource) throws IllegalArgumentException {}
    public String extractMetadata(int keyCode) { return null; }
    public android.graphics.Bitmap getFrameAtTime(long timeUs, int option) { return null; }
    public android.graphics.Bitmap getFrameAtTime(long timeUs) { return null; }
    public android.graphics.Bitmap getFrameAtTime() { return null; }
    public byte[] getEmbeddedPicture() { return null; }
    public void release() throws java.io.IOException {}
    @Override public void close() throws java.io.IOException { release(); }
}
