#pragma once
#include <android/binder_ibinder.h>
namespace android {
class IPCThreadState {
 public:
  static IPCThreadState* self() {
    thread_local IPCThreadState state;
    return &state;
  }
  int getCallingPid() const { return AIBinder_getCallingPid(); }
  int getCallingUid() const { return AIBinder_getCallingUid(); }
};
}  // namespace android
