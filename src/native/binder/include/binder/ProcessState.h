#pragma once
#include <android/binder_process.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/AndroidThreads.h>
namespace android {
class ProcessState : public virtual RefBase {
public:
  static sp<ProcessState> self() {
    static sp<ProcessState> state = sp<ProcessState>::make();
    return state;
  }
  void startThreadPool() { ABinderProcess_startThreadPool(); }
  // ProcessState names the joining thread; the hosted pool has no kernel driver id.
  void giveThreadPoolName() { androidSetThreadName("Binder:AZKB"); }
  status_t setThreadPoolMaxThreadCount(size_t count) {
    return count <= UINT32_MAX &&
                   ABinderProcess_setThreadPoolMaxThreadCount(count)
               ? OK
               : BAD_VALUE;
  }
  const char *getDriverName() const { return "AZKB"; }
};
} // namespace android
