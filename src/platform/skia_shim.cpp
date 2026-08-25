// extern-"C" shim over Skia C++ (see skia_shim.h). Compiled with zig c++,
// linked against nixpkgs libskia.so.
#include "skia_shim.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkColorSpace.h>
#include <SkFont.h>
#include <SkFontMgr.h>
#include <SkImageInfo.h>
#include <SkPaint.h>
#include <SkSurface.h>
#include <SkTypeface.h>
#include <SkFontMgr_fontconfig.h>
#include <SkFontScanner_FreeType.h>

#include <fontconfig/fontconfig.h>

struct azk_surface {
    sk_sp<SkSurface> surface;
};

static SkColor color_from_argb(uint32_t argb) {
    return static_cast<SkColor>(argb);
}

extern "C" azk_surface *azk_surface_wrap_pixels(int width, int height, size_t row_bytes, void *pixels) {
    SkImageInfo info = SkImageInfo::Make(width, height, kBGRA_8888_SkColorType, kPremul_SkAlphaType);
    sk_sp<SkSurface> s = SkSurfaces::WrapPixels(info, pixels, row_bytes);
    if (!s) return nullptr;
    auto *out = new azk_surface();
    out->surface = std::move(s);
    return out;
}

extern "C" void azk_surface_destroy(azk_surface *s) {
    delete s;
}

extern "C" azk_canvas *azk_surface_canvas(azk_surface *s) {
    return reinterpret_cast<azk_canvas *>(s->surface->getCanvas());
}

extern "C" void azk_surface_flush(azk_surface *s) {
    (void)s; // raster surface: pixels are already in place
}

static SkCanvas *canvas(azk_canvas *c) {
    return reinterpret_cast<SkCanvas *>(c);
}

extern "C" void azk_canvas_clear(azk_canvas *c, uint32_t argb) {
    canvas(c)->clear(color_from_argb(argb));
}

extern "C" void azk_canvas_draw_rect(azk_canvas *c, float l, float t, float r, float b, uint32_t argb) {
    SkPaint paint;
    paint.setColor(color_from_argb(argb));
    paint.setAntiAlias(true);
    canvas(c)->drawRect(SkRect::MakeLTRB(l, t, r, b), paint);
}

extern "C" void azk_canvas_draw_circle(azk_canvas *c, float cx, float cy, float radius, uint32_t argb) {
    SkPaint paint;
    paint.setColor(color_from_argb(argb));
    paint.setAntiAlias(true);
    canvas(c)->drawCircle(cx, cy, radius, paint);
}

static sk_sp<SkFontMgr> font_mgr() {
    static sk_sp<SkFontMgr> mgr = [] {
        FcConfig *fc = FcInitLoadConfigAndFonts();
        return SkFontMgr_New_FontConfig(fc, SkFontScanner_Make_FreeType());
    }();
    return mgr;
}

extern "C" void azk_canvas_draw_text(azk_canvas *c, const char *utf8, float x, float y, float size, uint32_t argb) {
    sk_sp<SkTypeface> tf = font_mgr()->matchFamilyStyle(nullptr, SkFontStyle::Normal());
    if (!tf) tf = font_mgr()->legacyMakeTypeface(nullptr, SkFontStyle::Normal());
    SkFont font(tf, size);
    font.setEdging(SkFont::Edging::kAntiAlias);
    SkPaint paint;
    paint.setColor(color_from_argb(argb));
    canvas(c)->drawSimpleText(utf8, strlen(utf8), SkTextEncoding::kUTF8, x, y, font, paint);
}
