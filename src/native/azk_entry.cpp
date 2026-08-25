// artzig island entry points callable from the Zig launcher (C ABI).
#include <SkBitmap.h>
#include <SkImageInfo.h>
#include <SkRect.h>
#include <hwui/Canvas.h>
#include <hwui/Paint.h>

extern "C" {

// M1 smoke: draw through the real AOSP hwui software Canvas into an externally
// owned BGRA8888 buffer (the Wayland wl_shm mapping).
int azk_hwui_smoke(void* pixels, int width, int height, size_t row_bytes) {
    SkBitmap bitmap;
    bitmap.installPixels(
        SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType),
        pixels, row_bytes);
    android::Canvas* canvas = android::Canvas::create_canvas(bitmap);
    if (!canvas) return -1;

    canvas->drawColor(0xFF16161e, SkBlendMode::kSrc);

    android::Paint paint;
    paint.setAntiAlias(true);

    paint.setColor(0xFF7aa2f7);  // blue rounded rect
    canvas->drawRoundRect(width * 0.08f, height * 0.08f, width * 0.92f, height * 0.4f,
                          24.0f, 24.0f, paint);

    paint.setColor(0xFF9ece6a);  // green circle
    canvas->drawCircle(width * 0.3f, height * 0.68f, height * 0.18f, paint);

    paint.setColor(0xFFf7768e);  // red stroked path
    paint.setStyle(android::Paint::kStroke_Style);
    paint.setStrokeWidth(8.0f);
    SkPath path;
    path.moveTo(width * 0.5f, height * 0.5f);
    path.cubicTo(width * 0.6f, height * 0.9f, width * 0.8f, height * 0.45f,
                 width * 0.92f, height * 0.85f);
    canvas->drawPath(path, paint);

    delete canvas;
    return 0;
}
}
