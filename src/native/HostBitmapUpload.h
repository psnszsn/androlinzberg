#pragma once

#include <SkBitmap.h>
#include <jni.h>

namespace artzig {
// Stock GLUtils consumes Android RGBA pixels. Host Skia software bitmaps may
// store BGRA instead; retain a converted snapshot for the duration of the upload.
bool getGlBitmapPixels(JNIEnv* env, jobject bitmap, SkBitmap* upload);
}
