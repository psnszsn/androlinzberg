// Stock android_os_MessageQueue owns the thread's libutils Looper. The Wayland
// owner only borrows its poll state; nativePollOnce dispatches callbacks,
// drains wakes and rethrows callback exceptions through the pinned JNI
// implementation.
#include <utils/Looper.h>

extern "C" __attribute__((visibility("default"))) void
azk_looper_poll_state(int *fd, int *timeoutMillis) {
  const auto looper = android::Looper::getForThread();
  *fd = -1;
  *timeoutMillis = -1;
  if (looper != nullptr)
    looper->getHostPollState(fd, timeoutMillis);
}
