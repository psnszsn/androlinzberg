/* artzig stub of AOSP android.media.MediaMetadataRetriever: the real class is
 * libmedia_jni (stagefright metadata extraction), absent on this host. Keys are
 * the AOSP values; every extraction answers "no metadata" (ExifInterface then
 * treats HEIF/AVIF input as carrying no EXIF, the AOSP path for an extractor
 * failure). */
package android.media;
public class MediaMetadataRetriever implements AutoCloseable {
    /** AOSP metadata keys (the full public set; MediaMetadata maps the editor keys). */
    public static final int METADATA_KEY_CD_TRACK_NUMBER = 0;
    public static final int METADATA_KEY_ALBUM = 1;
    public static final int METADATA_KEY_ARTIST = 2;
    public static final int METADATA_KEY_AUTHOR = 3;
    public static final int METADATA_KEY_COMPOSER = 4;
    public static final int METADATA_KEY_DATE = 5;
    public static final int METADATA_KEY_GENRE = 6;
    public static final int METADATA_KEY_TITLE = 7;
    public static final int METADATA_KEY_YEAR = 8;
    public static final int METADATA_KEY_DURATION = 9;
    public static final int METADATA_KEY_NUM_TRACKS = 10;
    public static final int METADATA_KEY_WRITER = 11;
    public static final int METADATA_KEY_MIMETYPE = 12;
    public static final int METADATA_KEY_ALBUMARTIST = 13;
    public static final int METADATA_KEY_DISC_NUMBER = 14;
    public static final int METADATA_KEY_COMPILATION = 15;
    public static final int METADATA_KEY_HAS_AUDIO = 16;
    public static final int METADATA_KEY_BITRATE = 20;
    public static final int METADATA_KEY_TIMED_TEXT_LANGUAGES = 21;
    public static final int METADATA_KEY_IS_DRM = 22;
    public static final int METADATA_KEY_LOCATION = 23;
    public static final int METADATA_KEY_CAPTURE_FRAMERATE = 25;
    public static final int METADATA_KEY_IMAGE_COUNT = 27;
    public static final int METADATA_KEY_IMAGE_PRIMARY = 28;
    public static final int METADATA_KEY_VIDEO_FRAME_COUNT = 32;
    public static final int METADATA_KEY_COLOR_STANDARD = 35;
    public static final int METADATA_KEY_COLOR_TRANSFER = 36;
    public static final int METADATA_KEY_COLOR_RANGE = 37;
    public static final int METADATA_KEY_SAMPLERATE = 38;
    public static final int METADATA_KEY_BITS_PER_SAMPLE = 39;

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
