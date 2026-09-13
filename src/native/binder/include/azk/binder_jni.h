#pragma once
#include <azk/binder.h>
// Installed once by the native island before framework JNI registration.
// These callbacks keep libbinder_ndk usable without linking ART/the island.
struct AzkBinderJavaBridge {
  int (*registerNatives)(void *env);
  AIBinder *(*fromJava)(void *env, void *object);
  void *(*toJava)(void *env, AIBinder *binder);
  AParcel *(*parcelFromJava)(void *env, void *object);
  int32_t (*debugCount)(void *env, int kind);
};
extern "C" void azk_binder_installJavaBridge(const AzkBinderJavaBridge *);
