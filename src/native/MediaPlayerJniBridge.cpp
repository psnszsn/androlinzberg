// The pinned media.player registrar is private to its standalone library TU.
// Include it unchanged and expose only registration to the shared native island.
// Read jni.h before renaming the standalone loader, so the unused loader does
// not inherit JNIEXPORT and its unrelated library registrations can be stripped.
#include <jni.h>
#define JNI_OnLoad azk_unused_media_JNI_OnLoad
#include "android_media_MediaPlayer.cpp"
#undef JNI_OnLoad

int azk_register_media_player(JNIEnv* env) {
    return register_android_media_MediaPlayer(env);
}
