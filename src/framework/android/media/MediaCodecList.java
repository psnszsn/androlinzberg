/* artzig shim: static table of the ffmpeg-backed decoders. */
package android.media;

public final class MediaCodecList {
    public static final int REGULAR_CODECS = 0;
    public static final int ALL_CODECS = 1;

    private static final String[][] TABLE = {
        // { codec name, mime, ffmpeg decoder name }
        { "c2.artzig.aac.decoder", MediaFormat.MIMETYPE_AUDIO_AAC, "aac" },
        { "c2.artzig.opus.decoder", MediaFormat.MIMETYPE_AUDIO_OPUS, "opus" },
        { "c2.artzig.mp3.decoder", MediaFormat.MIMETYPE_AUDIO_MPEG, "mp3" },
        { "c2.artzig.flac.decoder", MediaFormat.MIMETYPE_AUDIO_FLAC, "flac" },
        { "c2.artzig.vorbis.decoder", MediaFormat.MIMETYPE_AUDIO_VORBIS, "vorbis" },
        { "c2.artzig.avc.decoder", MediaFormat.MIMETYPE_VIDEO_AVC, "h264" },
        { "c2.artzig.vp9.decoder", MediaFormat.MIMETYPE_VIDEO_VP9, "vp9" },
        { "c2.artzig.mpeg4.decoder", "video/mp4v-es", "mpeg4" },
    };

    private static final MediaCodecInfo[] INFOS;
    static {
        INFOS = new MediaCodecInfo[TABLE.length];
        for (int i = 0; i < TABLE.length; i++) {
            INFOS[i] = new MediaCodecInfo(TABLE[i][0], new String[] { TABLE[i][1] });
        }
    }

    public MediaCodecList(int kind) {}

    public MediaCodecInfo[] getCodecInfos() { return INFOS.clone(); }

    public String findDecoderForFormat(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        return decoderNameForMime(mime);
    }
    public String findEncoderForFormat(MediaFormat format) { return null; }

    static String decoderNameForMime(String mime) {
        if (mime == null) return null;
        for (String[] row : TABLE) {
            if (row[1].equalsIgnoreCase(mime)) return row[0];
        }
        return null;
    }

    static String ffmpegNameFor(String codecName) {
        for (String[] row : TABLE) {
            if (row[0].equals(codecName)) return row[2];
        }
        return null;
    }

    static MediaCodecInfo infoFor(String codecName) {
        for (MediaCodecInfo i : INFOS) {
            if (i.getName().equals(codecName)) return i;
        }
        return null;
    }
}
