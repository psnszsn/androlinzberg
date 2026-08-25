package dev.artzig;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.ArtzigVideoLayer;
import android.view.Surface;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Video-layer test: decodes /tmp/azk-test.m4v (u32le-size-prefixed mpeg4
 * frames) through MediaCodec into a Wayland subsurface, while the UI bitmap
 * punches a transparent hole. draw() is called per frame by the launcher.
 */
public final class VideoHost {
    private static Surface sSurface;
    private static MediaCodec sCodec;
    private static ByteBuffer sStream;
    private static MediaCodec.BufferInfo sInfo = new MediaCodec.BufferInfo();
    private static boolean sInputDone, sOutputDone;
    private static int sFrames;
    private static final int VX = 480, VY = 200, VW = 320, VH = 240;

    public static void motion(int action, float x, float y) {}

    public static Bitmap draw(int width, int height) throws Exception {
        if (sSurface == null) init();
        pump();
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF203040);
        Paint p = new Paint();
        p.setColor(Color.WHITE);
        p.setTextSize(28);
        c.drawText("artzig video layer: " + sFrames + " frames"
                + (sOutputDone ? " (done)" : ""), 40, 80, p);
        // punch the hole for the subsurface
        p.setColor(Color.TRANSPARENT);
        p.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        c.drawRect(VX, VY, VX + VW, VY + VH, p);
        return bmp;
    }

    private static void init() throws Exception {
        android.graphics.Typeface.loadPreinstalledSystemFontMap();
        byte[] data;
        try (FileInputStream in = new FileInputStream("/tmp/azk-test.m4v")) {
            data = in.readAllBytes();
        }
        sStream = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        sSurface = ArtzigVideoLayer.createSurface();
        ArtzigVideoLayer.setRect(sSurface, VX, VY, VW, VH);
        sCodec = MediaCodec.createDecoderByType("video/mp4v-es");
        MediaFormat fmt = MediaFormat.createVideoFormat("video/mp4v-es", 320, 240);
        sCodec.configure(fmt, sSurface, null, 0);
        sCodec.start();
        System.out.println("VideoHost: stream bytes=" + data.length);
    }

    private static void pump() {
        if (sOutputDone) return;
        for (int spin = 0; spin < 4; spin++) {
            if (!sInputDone) {
                int idx = sCodec.dequeueInputBuffer(0);
                if (idx >= 0) {
                    if (sStream.remaining() < 4) {
                        sCodec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        sInputDone = true;
                    } else {
                        int size = sStream.getInt();
                        ByteBuffer in = sCodec.getInputBuffer(idx);
                        byte[] tmp = new byte[size];
                        sStream.get(tmp);
                        in.put(tmp);
                        sCodec.queueInputBuffer(idx, 0, size, sFrames * 33333L, 0);
                    }
                }
            }
            int idx = sCodec.dequeueOutputBuffer(sInfo, 0);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = sCodec.getOutputFormat();
                System.out.println("VideoHost: output " + f.getInteger(MediaFormat.KEY_WIDTH)
                        + "x" + f.getInteger(MediaFormat.KEY_HEIGHT));
            } else if (idx >= 0) {
                if ((sInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sOutputDone = true;
                    System.out.println("VideoHost: EOS after " + sFrames + " frames");
                }
                boolean render = sInfo.size > 0;
                sCodec.releaseOutputBuffer(idx, render);
                if (render) { sFrames++; break; } // one rendered frame per draw tick
            } else {
                break;
            }
        }
    }
}
