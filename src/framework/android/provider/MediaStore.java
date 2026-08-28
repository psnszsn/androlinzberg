/*
 * Hosted adaptation following Android 15 MediaStore.Images/Audio/Video/Downloads and
 * InternalThumbnails.getThumbnail, plus Android 16 MediaStore.openAssetFileDescriptor. The
 * MediaProvider/storage-service module is not available in-process, so its source cannot be
 * compiled directly; provider operations stay behind ContentResolver while AOSP URI construction,
 * thumbnail sizing, cancellation, return values, and public signatures are preserved here.
 */
package android.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;
import android.util.Size;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class MediaStore {
    private static final String TAG = "MediaStore";

    public static final String AUTHORITY = "media";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);
    public static final String VOLUME_INTERNAL = "internal";
    public static final String VOLUME_EXTERNAL = "external";
    public static final String VOLUME_EXTERNAL_PRIMARY = "external_primary";
    public static final String PARAM_REQUIRE_ORIGINAL = "requireOriginal";
    private static final int PICK_IMAGES_MAX_LIMIT = 100;

    public MediaStore() {}

    public static int getPickImagesMaxLimit() {
        return PICK_IMAGES_MAX_LIMIT;
    }

    public static Uri setRequireOriginal(Uri uri) {
        Objects.requireNonNull(uri, "uri");
        return uri.buildUpon().appendQueryParameter(PARAM_REQUIRE_ORIGINAL, "1").build();
    }

    public static AssetFileDescriptor openAssetFileDescriptor(ContentResolver resolver, Uri uri,
            String mode, CancellationSignal cancellationSignal) throws FileNotFoundException {
        Objects.requireNonNull(resolver, "resolver");
        return resolver.openAssetFileDescriptor(uri, mode, cancellationSignal);
    }

    public interface MediaColumns extends BaseColumns {
        String DATA = "_data";
        String SIZE = "_size";
        String DISPLAY_NAME = "_display_name";
        String TITLE = "title";
        String DATE_ADDED = "date_added";
        String DATE_MODIFIED = "date_modified";
        String MIME_TYPE = "mime_type";
        String WIDTH = "width";
        String HEIGHT = "height";
    }

    public interface DownloadColumns extends MediaColumns {
        String DOWNLOAD_URI = "download_uri";
        String REFERER_URI = "referer_uri";
    }

    public static final class Downloads implements DownloadColumns {
        public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
        public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/download";

        private Downloads() {}

        public static Uri getContentUri(String volumeName) {
            return contentUri(volumeName, "downloads");
        }

        public static Uri getContentUri(String volumeName, long id) {
            return ContentUris.withAppendedId(getContentUri(volumeName), id);
        }
    }

    public static final class Images {
        public Images() {}

        public interface ImageColumns extends MediaColumns {
            String DESCRIPTION = "description";
            String IS_PRIVATE = "isprivate";
            String LATITUDE = "latitude";
            String LONGITUDE = "longitude";
            String DATE_TAKEN = "datetaken";
            String ORIENTATION = "orientation";
            String BUCKET_ID = "bucket_id";
            String BUCKET_DISPLAY_NAME = "bucket_display_name";
        }

        public static final class Media implements ImageColumns {
            public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
            public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/image";
            public static final String DEFAULT_SORT_ORDER = BUCKET_DISPLAY_NAME;

            public Media() {}

            public static Uri getContentUri(String volumeName) {
                return contentUri(volumeName, "images", "media");
            }

            public static Uri getContentUri(String volumeName, long id) {
                return ContentUris.withAppendedId(getContentUri(volumeName), id);
            }
        }

        @Deprecated
        public static class Thumbnails implements BaseColumns {
            public static final int MINI_KIND = ThumbnailConstants.MINI_KIND;
            public static final int FULL_SCREEN_KIND = ThumbnailConstants.FULL_SCREEN_KIND;
            public static final int MICRO_KIND = ThumbnailConstants.MICRO_KIND;
            public static final String DATA = "_data";
            public static final String IMAGE_ID = "image_id";
            public static final String KIND = "kind";
            public static final String WIDTH = "width";
            public static final String HEIGHT = "height";
            public static final String THUMB_DATA = "_thumb_data";
            public static final String DEFAULT_SORT_ORDER = "image_id ASC";
            public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
            public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);

            public static Bitmap getThumbnail(ContentResolver resolver, long imageId, int kind,
                    BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(resolver,
                        ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, imageId), kind);
            }

            public static Uri getContentUri(String volumeName) {
                return contentUri(volumeName, "images", "thumbnails");
            }

            public static Size getKindSize(int kind) {
                return ThumbnailConstants.getKindSize(kind);
            }
        }
    }

    public static final class Audio {
        public Audio() {}

        public interface AudioColumns extends MediaColumns {
            String TITLE_KEY = "title_key";
        }

        public static final class Media implements AudioColumns {
            public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
            public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/audio";
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/audio";
            public static final String DEFAULT_SORT_ORDER = TITLE_KEY;

            public Media() {}

            public static Uri getContentUri(String volumeName) {
                return contentUri(volumeName, "audio", "media");
            }

            public static Uri getContentUri(String volumeName, long id) {
                return ContentUris.withAppendedId(getContentUri(volumeName), id);
            }
        }
    }

    public static final class Video {
        public Video() {}

        public interface VideoColumns extends MediaColumns {
            String DURATION = "duration";
            String RESOLUTION = "resolution";
            String DATE_TAKEN = "datetaken";
            String BUCKET_ID = "bucket_id";
            String BUCKET_DISPLAY_NAME = "bucket_display_name";
        }

        public static final class Media implements VideoColumns {
            public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
            public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/video";
            public static final String DEFAULT_SORT_ORDER = TITLE;

            public Media() {}

            public static Uri getContentUri(String volumeName) {
                return contentUri(volumeName, "video", "media");
            }

            public static Uri getContentUri(String volumeName, long id) {
                return ContentUris.withAppendedId(getContentUri(volumeName), id);
            }
        }

        @Deprecated
        public static class Thumbnails implements BaseColumns {
            public static final int MINI_KIND = ThumbnailConstants.MINI_KIND;
            public static final int FULL_SCREEN_KIND = ThumbnailConstants.FULL_SCREEN_KIND;
            public static final int MICRO_KIND = ThumbnailConstants.MICRO_KIND;
            public static final String DATA = "_data";
            public static final String VIDEO_ID = "video_id";
            public static final String KIND = "kind";
            public static final String WIDTH = "width";
            public static final String HEIGHT = "height";
            public static final String DEFAULT_SORT_ORDER = "video_id ASC";
            public static final Uri INTERNAL_CONTENT_URI = getContentUri(VOLUME_INTERNAL);
            public static final Uri EXTERNAL_CONTENT_URI = getContentUri(VOLUME_EXTERNAL);

            public static Bitmap getThumbnail(ContentResolver resolver, long videoId, int kind,
                    BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(resolver,
                        ContentUris.withAppendedId(Media.EXTERNAL_CONTENT_URI, videoId), kind);
            }

            public static Uri getContentUri(String volumeName) {
                return contentUri(volumeName, "video", "thumbnails");
            }

            public static Size getKindSize(int kind) {
                return ThumbnailConstants.getKindSize(kind);
            }
        }
    }

    private static final class ThumbnailConstants {
        static final int MINI_KIND = 1;
        static final int FULL_SCREEN_KIND = 2;
        static final int MICRO_KIND = 3;
        static final Size MINI_SIZE = new Size(512, 384);
        static final Size FULL_SCREEN_SIZE = new Size(1024, 786);
        static final Size MICRO_SIZE = new Size(96, 96);

        static Size getKindSize(int kind) {
            if (kind == MICRO_KIND) return MICRO_SIZE;
            if (kind == FULL_SCREEN_KIND) return FULL_SCREEN_SIZE;
            if (kind == MINI_KIND) return MINI_SIZE;
            throw new IllegalArgumentException("Unsupported kind: " + kind);
        }
    }

    private static final class InternalThumbnails {
        private static final Map<Uri, CancellationSignal> PENDING = new HashMap<>();

        static Bitmap getThumbnail(ContentResolver resolver, Uri uri, int kind) {
            CancellationSignal signal = new CancellationSignal();
            synchronized (PENDING) {
                PENDING.put(uri, signal);
            }
            try {
                return resolver.loadThumbnail(uri, ThumbnailConstants.getKindSize(kind), signal);
            } catch (IOException exception) {
                Log.w(TAG, "Failed to obtain thumbnail for " + uri, exception);
                return null;
            } finally {
                synchronized (PENDING) {
                    PENDING.remove(uri);
                }
            }
        }
    }

    private static Uri contentUri(String volumeName, String... path) {
        Objects.requireNonNull(volumeName, "volumeName");
        Uri.Builder builder = AUTHORITY_URI.buildUpon().appendPath(volumeName);
        for (String segment : path) builder.appendPath(segment);
        return builder.build();
    }
}
