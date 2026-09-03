/* artzig stub of AOSP android.media.RingtoneManager: the real class plays ringtones through
 * MediaPlayer/Ringtone (libmedia_jni natives) and queries the MediaStore ringtone provider,
 * neither of which exists in a hosted session. NotificationChannel's backup restore needs the
 * type constants and getRingtoneUriForRestore. Pending the audio family (managers over hosted
 * services, phase 3). */
package android.media;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.FileNotFoundException;

public class RingtoneManager {
    public static final int TYPE_RINGTONE = 1;
    public static final int TYPE_NOTIFICATION = 2;
    public static final int TYPE_ALARM = 4;
    public static final int TYPE_ALL = TYPE_RINGTONE | TYPE_NOTIFICATION | TYPE_ALARM;

    /**
     * AOSP RingtoneManager.getRingtoneUriForRestore reduced to the path without a media
     * provider: a null value restores null, anything else the (un)canonicalized URI.
     */
    public static Uri getRingtoneUriForRestore(ContentResolver contentResolver, String value,
            int ringtoneType) throws FileNotFoundException, IllegalArgumentException {
        if (value == null) {
            // Return a valid null. It means the null value is intended instead of a failure.
            return null;
        }
        final Uri canonicalUri = Uri.parse(value);
        final Uri ringtoneUri = contentResolver.uncanonicalize(canonicalUri);
        return ringtoneUri != null ? ringtoneUri : canonicalUri;
    }
}
