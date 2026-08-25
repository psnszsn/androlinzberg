/*
 * Minimal extern-"C" surface over Skia's C++ API (nixpkgs skia has no C API).
 * Phase M0/M1 scope: a raster SkSurface drawing directly into an externally
 * owned pixel buffer (the Wayland wl_shm mapping), plus the handful of canvas
 * ops the smoke tests need. The real android.graphics stack (M1+) talks to
 * Skia through AOSP's own hwui natives; this shim stays for the Zig side
 * (present/clear/debug) only.
 */
#ifndef ARTZIG_SKIA_SHIM_H
#define ARTZIG_SKIA_SHIM_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct azk_surface azk_surface; /* wraps sk_sp<SkSurface> */
typedef struct azk_canvas azk_canvas;   /* borrowed SkCanvas* */

/* BGRA8888 premul (Wayland argb8888 little-endian == Skia kBGRA_8888). */
azk_surface *azk_surface_wrap_pixels(int width, int height, size_t row_bytes, void *pixels);
void azk_surface_destroy(azk_surface *s);
azk_canvas *azk_surface_canvas(azk_surface *s);
void azk_surface_flush(azk_surface *s);

void azk_canvas_clear(azk_canvas *c, uint32_t argb);
void azk_canvas_draw_rect(azk_canvas *c, float l, float t, float r, float b, uint32_t argb);
void azk_canvas_draw_circle(azk_canvas *c, float cx, float cy, float radius, uint32_t argb);
/* Simple UTF-8 text with Skia's default font manager (fontconfig); size in px. */
void azk_canvas_draw_text(azk_canvas *c, const char *utf8, float x, float y, float size, uint32_t argb);

#ifdef __cplusplus
}
#endif

#endif
