#pragma once
#include <jni.h>

// ART's host libnativeloader omits this Android-only declaration. The existing host
// ClassLoaderFactory creates no linker namespaces; the documented lookup result is nullptr.
namespace android {
struct NativeLoaderNamespace;
extern "C" NativeLoaderNamespace* FindNativeLoaderNamespaceByClassLoader(JNIEnv*, jobject);
}
