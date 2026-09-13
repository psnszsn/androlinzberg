#pragma once
#include <azk/binder.h>
namespace android {
class IPCThreadState {
public:
  static constexpr int32_t kUnsetWorkSource = -1;
  // Unlike the Binder driver, AZKB never implicitly demotes dispatch workers.
  static void disableBackgroundScheduling(bool) {}
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
  bool hasExplicitIdentity() const { return azk_binder_hasExplicitIdentity(); }
  int32_t getStrictModePolicy() const { return azk_binder_getStrictModePolicy(); }
  void setStrictModePolicy(int32_t policy) { azk_binder_setStrictModePolicy(policy); }
  uint32_t getCallingWorkSourceUid() const { return azk_binder_getWorkSource(); }
  int64_t setCallingWorkSourceUid(uint32_t uid) { return azk_binder_setWorkSource(uid, true); }
  int64_t setCallingWorkSourceUidWithoutPropagation(uint32_t uid) { return azk_binder_setWorkSource(uid, false); }
  int64_t clearCallingWorkSource() { return setCallingWorkSourceUid(kUnsetWorkSource); }
  void restoreCallingWorkSource(int64_t token) { azk_binder_restoreWorkSource(token); }
  bool shouldPropagateWorkSource() const { return azk_binder_shouldPropagateWorkSource(); }
  int32_t getLastTransactionBinderFlags() const { return azk_binder_callFlags(); }
  void flushCommands() { azk_binder_flush(); }
  void blockUntilThreadAvailable() { azk_binder_blockUntilThreadAvailable(); }
  void joinThreadPool(bool isMain = true);
};
} // namespace android
