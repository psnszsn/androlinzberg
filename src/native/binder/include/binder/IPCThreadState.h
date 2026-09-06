#pragma once
#include <azk/binder.h>
namespace android {
class IPCThreadState {
public:
  static IPCThreadState *self() {
    thread_local IPCThreadState state;
    return &state;
  }
  static IPCThreadState *selfOrNull() { return self(); }
  int getCallingPid() const { return AIBinder_getCallingPid(); }
  int getCallingUid() const { return AIBinder_getCallingUid(); }
  const void *getServingStackPointer() const {
    return azk_binder_servingStack();
  }
  uint64_t clearCallingIdentity() { return azk_binder_clearCallingIdentity(); }
  void restoreCallingIdentity(uint64_t token) {
    azk_binder_restoreCallingIdentity(token);
  }
  void flushCommands() { azk_binder_flush(); }
  void blockUntilThreadAvailable() { azk_binder_blockUntilThreadAvailable(); }
  void joinThreadPool(bool isMain = true);
};
} // namespace android
