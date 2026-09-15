#pragma once
#include <jni.h>
#include <cstddef>

// AndroidRuntime uses Bionic's setprogname; the selected original implementation
// updates glibc's equivalent process-name storage at the host libc boundary.
extern "C" void setprogname(const char*);
extern "C" int azk_android_runtime_initialize(char* argument_block, size_t length);
extern "C" int azk_android_runtime_bind(JNIEnv*);
extern "C" int azk_register_runtime_init(JNIEnv*);
extern "C" [[noreturn]] void azk_android_runtime_exit(int);
