#pragma once
#include <binder/IBinder.h>
#include <android/binder_stability.h>
// Platform (non-VINTF) interfaces share the hosted process domain. VINTF
// service negotiation is unavailable and must not be advertised by this facade.
namespace android::internal {
class Stability {
public:
  template <class T> static void markCompilationUnit(T *) {}
  static void markVintf(IBinder *binder) {
    if (!binder) return;
    auto *native = binder->acquireNative();
    AIBinder_markVintfStability(native);
    AIBinder_decStrong(native);
  }
};
} // namespace android::internal
