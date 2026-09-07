// Host counterpart of AOSP NativeInputManager's dispatcher/JNI boundary.
// Wayland owns physical devices; stock InputDispatcher and SurfaceFlinger own
// routing and window metadata. No window geometry is inferred in this adapter.
#define LOG_TAG "HostInputDispatcher"
#include <InputDispatcher.h>
#include <android/os/BnInputFlinger.h>
#include <android_runtime/AndroidRuntime.h>
#include "android_util_Binder.h"
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <gui/SurfaceComposerClient.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include "android_hardware_input_InputApplicationHandle.h"
#include "android_view_InputChannel.h"
#include "android_view_KeyEvent.h"
#include "android_view_MotionEvent.h"
#include "core_jni_helpers.h"
#include <atomic>
#include <limits>
#include <map>
#include <mutex>

namespace android::hostinput {
using inputdispatcher::InputDispatcher;
using inputdispatcher::KeyEntry;
static thread_local unsigned callbackDepth;
static JavaVM *vm;
static jclass keyClass;
static jclass motionClass;
struct Methods {
  jmethodID noFocus, unresponsive, responsive, broken, focus, display, filter;
  jmethodID keyQueue, motionQueue, keyDispatch, unhandled, activity, outside;
  jmethodID capture, drop, interaction, unsupported;
} methods;

// InputDispatcher and Binder invoke policy on native threads. Attach only for
// the callback, bound local references, and journal Java exceptions before
// clearing them, as NativeInputManager does for asynchronous callbacks.
struct Callback {
  JNIEnv *env = nullptr;
  bool attached = false;
  Callback() {
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) ==
        JNI_EDETACHED) {
      attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK;
    }
    LOG_ALWAYS_FATAL_IF(!env, "Cannot attach input policy callback to ART");
    LOG_ALWAYS_FATAL_IF(env->PushLocalFrame(32) != JNI_OK,
                        "Input callback local frame failed");
    ++callbackDepth;
  }
  bool failed() {
    if (!env->ExceptionCheck())
      return false;
    jniLogException(env, ANDROID_LOG_ERROR, LOG_TAG, nullptr);
    env->ExceptionClear();
    return true;
  }
  ~Callback() {
    failed();
    env->PopLocalFrame(nullptr);
    --callbackDepth;
    if (attached)
      vm->DetachCurrentThread();
  }
};

class Policy final : public InputDispatcherPolicyInterface {
  jobject callbacks;

public:
  explicit Policy(JNIEnv *env, jobject value)
      : callbacks(env->NewGlobalRef(value)) {}
  ~Policy() override {
    // Cleanup may run while a JNI constructor failure is pending. Preserve that
    // exception instead of routing it through asynchronous callback handling.
    if (!callbacks)
      return;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) ==
        JNI_EDETACHED)
      attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK;
    LOG_ALWAYS_FATAL_IF(!env, "Cannot release input policy reference");
    env->DeleteGlobalRef(callbacks);
    if (attached)
      vm->DetachCurrentThread();
  }

  void notifyNoFocusedWindowAnr(
      const std::shared_ptr<InputApplicationHandle> &app) override {
    Callback c;
    auto obj = android_view_InputApplicationHandle_fromInputApplicationInfo(
        c.env, *app->getInfo());
    c.env->CallVoidMethod(callbacks, methods.noFocus, obj);
  }
  void notifyWindowUnresponsive(const sp<IBinder> &token,
                                std::optional<gui::Pid> pid,
                                const std::string &reason) override {
    Callback c;
    c.env->CallVoidMethod(
        callbacks, methods.unresponsive, javaObjectForIBinder(c.env, token),
        pid ? pid->val() : -1, c.env->NewStringUTF(reason.c_str()));
  }
  void notifyWindowResponsive(const sp<IBinder> &token,
                              std::optional<gui::Pid> pid) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.responsive,
                          javaObjectForIBinder(c.env, token),
                          pid ? pid->val() : -1);
  }
  void notifyInputChannelBroken(const sp<IBinder> &token) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.broken,
                          javaObjectForIBinder(c.env, token));
  }
  void notifyFocusChanged(const sp<IBinder> &oldToken,
                          const sp<IBinder> &newToken) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.focus,
                          javaObjectForIBinder(c.env, oldToken),
                          javaObjectForIBinder(c.env, newToken));
  }
  void unsupported(const char *kind) {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.unsupported,
                          c.env->NewStringUTF(kind));
  }
  void notifySensorEvent(int32_t, InputDeviceSensorType,
                         InputDeviceSensorAccuracy, nsecs_t,
                         const std::vector<float> &) override {
    unsupported("sensor event");
  }
  void notifySensorAccuracy(int32_t, InputDeviceSensorType,
                            InputDeviceSensorAccuracy) override {
    unsupported("sensor accuracy");
  }
  void notifyVibratorState(int32_t, bool) override {
    unsupported("vibrator state");
  }
  void notifySwitch(nsecs_t, uint32_t, uint32_t, uint32_t) override {
    unsupported("hardware switch");
  }
  void notifyFocusedDisplayChanged(ui::LogicalDisplayId display) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.display, display.val());
  }
  bool filterInputEvent(const InputEvent &event, uint32_t flags) override {
    // As in NativeInputManager::filterInputEvent, ownership of this copy passes
    // to Java: an InputFilter may enqueue it for asynchronous processing.
    Callback c;
    auto obj = event.getType() == InputEventType::KEY
                   ? android_view_KeyEvent_obtainAsCopy(
                         c.env, static_cast<const KeyEvent &>(event))
                   : android_view_MotionEvent_obtainAsCopy(
                         c.env, static_cast<const MotionEvent &>(event));
    const bool result =
        c.env->CallBooleanMethod(callbacks, methods.filter, obj.get(), flags);
    return c.failed() ? true : result;
  }
  void interceptKeyBeforeQueueing(const KeyEvent &event,
                                  uint32_t &flags) override {
    Callback c;
    auto obj = android_view_KeyEvent_obtainAsCopy(c.env, event);
    const auto result =
        c.env->CallIntMethod(callbacks, methods.keyQueue, obj.get(), flags);
    flags = c.failed() ? flags & ~POLICY_FLAG_PASS_TO_USER : result;
    android_view_KeyEvent_recycle(c.env, obj.get());
  }
  void interceptMotionBeforeQueueing(ui::LogicalDisplayId display,
                                     uint32_t source, int32_t action,
                                     nsecs_t when, uint32_t &flags) override {
    Callback c;
    const auto result =
        c.env->CallIntMethod(callbacks, methods.motionQueue, display.val(),
                             source, action, static_cast<jlong>(when), flags);
    flags = c.failed() ? flags & ~POLICY_FLAG_PASS_TO_USER : result;
  }
  std::variant<nsecs_t, KeyEntry::InterceptKeyResult>
  interceptKeyBeforeDispatching(const sp<IBinder> &token, const KeyEvent &event,
                                uint32_t flags) override {
    if (!(flags & POLICY_FLAG_TRUSTED))
      return KeyEntry::InterceptKeyResult::CONTINUE;
    Callback c;
    auto obj = android_view_KeyEvent_obtainAsCopy(c.env, event);
    const auto delay = c.env->CallLongMethod(callbacks, methods.keyDispatch,
                                             javaObjectForIBinder(c.env, token),
                                             obj.get(), flags);
    const bool failed = c.failed();
    android_view_KeyEvent_recycle(c.env, obj.get());
    if (failed)
      return KeyEntry::InterceptKeyResult::CONTINUE;
    if (delay == -1)
      return KeyEntry::InterceptKeyResult::SKIP;
    if (delay == -2)
      return KeyEntry::InterceptKeyResult::FALLBACK;
    if (delay <= 0)
      return KeyEntry::InterceptKeyResult::CONTINUE;
    return milliseconds_to_nanoseconds(
        std::min<jlong>(delay, std::numeric_limits<nsecs_t>::max() / 1000000));
  }
  std::optional<KeyEvent> dispatchUnhandledKey(const sp<IBinder> &token,
                                               const KeyEvent &event,
                                               uint32_t flags) override {
    if (!(flags & POLICY_FLAG_TRUSTED))
      return {};
    Callback c;
    auto obj = android_view_KeyEvent_obtainAsCopy(c.env, event);
    jobject result = c.env->CallObjectMethod(callbacks, methods.unhandled,
                                             javaObjectForIBinder(c.env, token),
                                             obj.get(), flags);
    std::optional<KeyEvent> fallback;
    if (!c.failed() && result)
      fallback = android_view_KeyEvent_obtainAsCopy(c.env, result);
    if (result && !c.env->IsSameObject(result, obj.get()))
      android_view_KeyEvent_recycle(c.env, result);
    android_view_KeyEvent_recycle(c.env, obj.get());
    return fallback;
  }
  void pokeUserActivity(nsecs_t time, int32_t type,
                        ui::LogicalDisplayId display) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.activity, static_cast<jlong>(time),
                          type, display.val());
  }
  void onPointerDownOutsideFocus(const sp<IBinder> &token) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.outside,
                          javaObjectForIBinder(c.env, token));
  }
  void setPointerCapture(const PointerCaptureRequest &request) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.capture,
                          javaObjectForIBinder(c.env, request.window),
                          request.seq);
  }
  void notifyDropWindow(const sp<IBinder> &token, float x, float y) override {
    Callback c;
    c.env->CallVoidMethod(callbacks, methods.drop,
                          javaObjectForIBinder(c.env, token), x, y);
  }
  void notifyDeviceInteraction(DeviceId device, nsecs_t time,
                               const std::set<gui::Uid> &uids) override {
    Callback c;
    auto array = c.env->NewIntArray(uids.size());
    std::vector<jint> values;
    for (auto uid : uids)
      values.push_back(uid.val());
    c.env->SetIntArrayRegion(array, 0, values.size(), values.data());
    c.env->CallVoidMethod(callbacks, methods.interaction, device,
                          static_cast<jlong>(time), array);
  }
};

struct State;
// SF can retain Binder endpoints after the Java owner closes. Weak references
// reject late transactions without keeping the dispatcher or policy alive.
class FocusService final : public os::BnInputFlinger {
  std::weak_ptr<State> state;

public:
  explicit FocusService(const std::shared_ptr<State> &value) : state(value) {}
  binder::Status createInputChannel(const std::string &,
                                    os::InputChannelCore *) override;
  binder::Status removeInputChannel(const sp<IBinder> &) override;
  binder::Status setFocusedWindow(const gui::FocusRequest &) override;
};
class WindowListener final : public gui::WindowInfosListener {
  std::weak_ptr<State> state;

public:
  explicit WindowListener(const std::shared_ptr<State> &value) : state(value) {}
  void onWindowInfosChanged(const gui::WindowInfosUpdate &) override;
};
struct State {
  Policy policy;
  InputDispatcher dispatcher;
  std::mutex connectionMutex;
  // SF remains the window-focus authority within Android. The compositor can
  // withdraw the whole desktop seat without changing ATMS's focused task.
  std::mutex desktopFocusMutex;
  bool desktopKeyboardFocused = true;
  std::map<int32_t, gui::FocusRequest> windowFocusRequests;
  void windowFocus(const gui::FocusRequest &request) {
    std::lock_guard lock(desktopFocusMutex);
    windowFocusRequests[request.displayId] = request;
    if (desktopKeyboardFocused)
      dispatcher.setFocusedWindow(request);
  }
  void desktopFocus(bool focused) {
    std::lock_guard lock(desktopFocusMutex);
    if (desktopKeyboardFocused == focused)
      return;
    desktopKeyboardFocused = focused;
    for (const auto &[display, requested] : windowFocusRequests) {
      gui::FocusRequest effective = requested;
      if (!focused) {
        effective.token.clear();
        effective.windowName = "Wayland keyboard left session";
      }
      dispatcher.setFocusedWindow(effective);
    }
  }
  std::atomic<bool> closed{false};
  std::atomic<bool> keyRepeatEnabled{true};
  sp<SurfaceComposerClient> client;
  sp<WindowListener> listener;
  sp<FocusService> focus;
  State(JNIEnv *env, jobject callbacks)
      : policy(env, callbacks), dispatcher(policy) {}
  void close() {
    if (closed.exchange(true))
      return;
    {
      std::lock_guard lock(connectionMutex);
      if (client && listener)
        client->removeWindowInfosListener(listener);
      listener.clear();
      client.clear();
      focus.clear();
    }
    dispatcher.stop();
  }
  ~State() { close(); }
};
static std::mutex registryMutex;
static std::map<jlong, std::shared_ptr<State>> registry;
static jlong nextHandle = 1;
static std::shared_ptr<State> get(JNIEnv *env, jlong handle) {
  std::lock_guard lock(registryMutex);
  auto it = registry.find(handle);
  if (it != registry.end() && !it->second->closed)
    return it->second;
  jniThrowException(env, "java/lang/IllegalStateException",
                    "Input dispatcher is closed");
  return {};
}
static void status(JNIEnv *env, status_t result, const char *operation) {
  if (result != OK)
    jniThrowExceptionFmt(env, "java/lang/IllegalStateException",
                         "%s failed: %d", operation, result);
}
static bool systemCaller() {
  const auto uid = IPCThreadState::self()->getCallingUid();
  return uid == 0 || uid == 1000;
}
binder::Status FocusService::createInputChannel(const std::string &name,
                                                os::InputChannelCore *out) {
  if (!systemCaller())
    return binder::Status::fromStatusT(PERMISSION_DENIED);
  auto owner = state.lock();
  if (!owner || owner->closed)
    return binder::Status::fromStatusT(DEAD_OBJECT);
  auto channel = owner->dispatcher.createInputChannel(name);
  if (!channel.ok())
    return binder::Status::fromStatusT(UNKNOWN_ERROR);
  InputChannel::moveChannel(std::move(*channel), *out);
  return binder::Status::ok();
}
binder::Status FocusService::removeInputChannel(const sp<IBinder> &token) {
  if (!systemCaller())
    return binder::Status::fromStatusT(PERMISSION_DENIED);
  auto owner = state.lock();
  if (!owner || owner->closed)
    return binder::Status::fromStatusT(DEAD_OBJECT);
  return binder::Status::fromStatusT(
      owner->dispatcher.removeInputChannel(token));
}
binder::Status
FocusService::setFocusedWindow(const gui::FocusRequest &request) {
  if (!systemCaller())
    return binder::Status::fromStatusT(PERMISSION_DENIED);
  auto owner = state.lock();
  if (!owner || owner->closed)
    return binder::Status::fromStatusT(DEAD_OBJECT);
  owner->windowFocus(request);
  return binder::Status::ok();
}
void WindowListener::onWindowInfosChanged(
    const gui::WindowInfosUpdate &update) {
  auto owner = state.lock();
  if (owner && !owner->closed)
    owner->dispatcher.onWindowInfosChanged(update);
}

static jlong create(JNIEnv *env, jclass, jobject callbacks) {
  auto state = std::make_shared<State>(env, callbacks);
  if (env->ExceptionCheck())
    return 0;
  const auto result = state->dispatcher.start();
  if (result != OK) {
    status(env, result, "start");
    return 0;
  }
  std::lock_guard lock(registryMutex);
  const jlong handle = nextHandle++;
  registry.emplace(handle, std::move(state));
  return handle;
}
static void close(JNIEnv *env, jclass, jlong handle) {
  // stop joins the dispatcher thread. Reject reentrant shutdown from policy
  // callbacks; owners can schedule close on their lifecycle thread instead.
  if (callbackDepth) {
    jniThrowException(env, "java/lang/IllegalStateException",
                      "Cannot close dispatcher from an input callback");
    return;
  }
  std::shared_ptr<State> owner;
  {
    std::lock_guard lock(registryMutex);
    auto node = registry.extract(handle);
    if (node.empty())
      return;
    owner = std::move(node.mapped());
  }
  owner->close();
}
static jobject createChannel(JNIEnv *env, jclass, jlong handle, jstring name) {
  auto owner = get(env, handle);
  if (!owner)
    return nullptr;
  ScopedUtfChars chars(env, name);
  if (!chars.c_str())
    return nullptr;
  auto channel = owner->dispatcher.createInputChannel(chars.c_str());
  if (!channel.ok()) {
    jniThrowException(env, "java/lang/IllegalStateException",
                      channel.error().message().c_str());
    return nullptr;
  }
  return android_view_InputChannel_createJavaObject(env,
                                                    std::move(channel.value()));
}
static jobject createMonitor(JNIEnv *env, jclass, jlong handle, jstring name,
                             jint display, jint pid) {
  auto owner = get(env, handle);
  if (!owner)
    return nullptr;
  ScopedUtfChars chars(env, name);
  if (!chars.c_str())
    return nullptr;
  auto channel = owner->dispatcher.createInputMonitor(
      ui::LogicalDisplayId{display}, chars.c_str(), gui::Pid{pid});
  if (!channel.ok()) {
    jniThrowException(env, "java/lang/IllegalStateException",
                      channel.error().message().c_str());
    return nullptr;
  }
  return android_view_InputChannel_createJavaObject(env,
                                                    std::move(channel.value()));
}
static void displayRemoved(JNIEnv *env, jclass, jlong handle, jint display) {
  auto owner = get(env, handle);
  if (owner) {
    std::lock_guard lock(owner->desktopFocusMutex);
    owner->windowFocusRequests.erase(display);
    owner->dispatcher.displayRemoved(ui::LogicalDisplayId{display});
  }
}
static void removeChannel(JNIEnv *env, jclass, jlong handle, jobject token) {
  auto owner = get(env, handle);
  if (!owner)
    return;
  // Pinned NativeInputManager.nativeRemoveInputChannel tolerates BAD_VALUE:
  // InputDispatcher may already have removed a broken channel before WMS teardown.
  const status_t result = owner->dispatcher.removeInputChannel(ibinderForJavaObject(env, token));
  if (result != BAD_VALUE) status(env, result, "removeInputChannel");
}
static jboolean pointerInWindow(JNIEnv *env, jclass, jlong handle, jobject token,
                                 jint display, jint device, jint pointer) {
  auto owner = get(env, handle);
  return owner && owner->dispatcher.isPointerInWindow(ibinderForJavaObject(env, token),
      ui::LogicalDisplayId{display}, device, pointer);
}
static void focusedApplication(JNIEnv *env, jclass, jlong handle, jint display,
                               jobject app) {
  auto owner = get(env, handle);
  if (!owner)
    return;
  // NativeInputManager refreshes the Java application's token, name and ANR
  // timeout before giving the handle to InputDispatcher. A window need not
  // exist yet to refresh this information on its behalf.
  if (!app) {
    owner->dispatcher.setFocusedApplication(ui::LogicalDisplayId{display}, nullptr);
    return;
  }
  auto application = android_view_InputApplicationHandle_getHandle(env, app);
  application->updateInfo();
  owner->dispatcher.setFocusedApplication(ui::LogicalDisplayId{display},
                                          application);
}
static void focusedDisplay(JNIEnv *env, jclass, jlong handle, jint display) {
  auto owner = get(env, handle);
  if (owner)
    owner->dispatcher.setFocusedDisplay(ui::LogicalDisplayId{display});
}
static void desktopKeyboardFocus(JNIEnv *env, jclass, jlong handle, jboolean focused) {
  auto owner = get(env, handle);
  if (owner)
    owner->desktopFocus(focused);
}
static void dispatchMode(JNIEnv *env, jclass, jlong handle, jboolean enabled,
                         jboolean frozen) {
  auto owner = get(env, handle);
  if (owner)
    owner->dispatcher.setInputDispatchMode(enabled, frozen);
}
static void keyRepeatConfiguration(JNIEnv *env, jclass, jlong handle,
                                   jint timeout, jint delay, jboolean enabled) {
  auto owner = get(env, handle);
  if (owner) {
    owner->keyRepeatEnabled.store(enabled);
    owner->dispatcher.setKeyRepeatConfiguration(std::chrono::milliseconds(timeout),
                                                std::chrono::milliseconds(delay), enabled);
  }
}
static void filterEnabled(JNIEnv *env, jclass, jlong handle, jboolean enabled) {
  auto owner = get(env, handle);
  if (owner)
    owner->dispatcher.setInputFilterEnabled(enabled);
}
static jboolean touchMode(JNIEnv *env, jclass, jlong handle, jboolean touch,
                          jint pid, jint uid, jboolean permission,
                          jint display) {
  auto owner = get(env, handle);
  return owner &&
         owner->dispatcher.setInTouchMode(
             touch, gui::Pid{pid}, gui::Uid{static_cast<uint32_t>(uid)},
             permission, ui::LogicalDisplayId{display});
}
static jboolean transfer(JNIEnv *env, jclass, jlong handle, jobject from,
                         jobject to, jboolean drag, jboolean entire) {
  auto owner = get(env, handle);
  return owner && owner->dispatcher.transferTouchGesture(
                      ibinderForJavaObject(env, from),
                      ibinderForJavaObject(env, to), drag, entire);
}
static jboolean transferDisplay(JNIEnv *env, jclass, jlong handle, jobject to,
                                jint display) {
  auto owner = get(env, handle);
  return owner &&
         owner->dispatcher.transferTouchOnDisplay(
             ibinderForJavaObject(env, to), ui::LogicalDisplayId{display});
}
static void pilfer(JNIEnv *env, jclass, jlong handle, jobject token) {
  auto owner = get(env, handle);
  if (owner)
    status(env,
           owner->dispatcher.pilferPointers(ibinderForJavaObject(env, token)),
           "pilferPointers");
}
static void capture(JNIEnv *env, jclass, jlong handle, jobject token,
                    jboolean enabled) {
  auto owner = get(env, handle);
  if (owner)
    owner->dispatcher.requestPointerCapture(ibinderForJavaObject(env, token),
                                            enabled);
}
static void captureChanged(JNIEnv *env, jclass, jlong handle, jobject token,
                           jint sequence) {
  auto owner = get(env, handle);
  if (!owner)
    return;
  owner->dispatcher.notifyPointerCaptureChanged(NotifyPointerCaptureChangedArgs{
      InputEvent::nextId(), systemTime(SYSTEM_TIME_MONOTONIC),
      PointerCaptureRequest{ibinderForJavaObject(env, token),
                            static_cast<uint32_t>(sequence)}});
}
static void eligibility(JNIEnv *env, jclass, jlong handle, jint display,
                        jboolean eligible) {
  auto owner = get(env, handle);
  if (owner)
    owner->dispatcher.setDisplayEligibilityForPointerCapture(
        ui::LogicalDisplayId{display}, eligible);
}
static jint inject(JNIEnv *env, jclass, jlong handle, jobject event, jint uid,
                   jint sync, jint timeout, jint flags) {
  auto owner = get(env, handle);
  if (!owner)
    return static_cast<jint>(os::InputEventInjectionResult::FAILED);
  std::optional<gui::Uid> target;
  if (uid >= 0)
    target = gui::Uid{static_cast<uint32_t>(uid)};
  auto mode = static_cast<os::InputEventInjectionSync>(sync);
  if (env->IsInstanceOf(event, keyClass)) {
    auto key = android_view_KeyEvent_obtainAsCopy(env, event);
    return static_cast<jint>(owner->dispatcher.injectInputEvent(
        &key, target, mode, std::chrono::milliseconds(timeout), flags));
  }
  if (env->IsInstanceOf(event, motionClass)) {
    auto motion = android_view_MotionEvent_getNativePtr(env, event);
    return static_cast<jint>(owner->dispatcher.injectInputEvent(
        motion, target, mode, std::chrono::milliseconds(timeout), flags));
  }
  jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Expected KeyEvent or MotionEvent");
  return static_cast<jint>(os::InputEventInjectionResult::FAILED);
}
// The compositor replaces InputReader, so these are device notifications. The
// separate injection API intentionally bypasses filtering and is used when an
// InputFilter sends its result back. Preserve batched samples and transform
// Java coordinates into the display space, as stock injection does.
static void notifyEvent(JNIEnv *env, jclass, jlong handle, jobject event, jboolean repeatable) {
  auto owner = get(env, handle);
  if (!owner)
    return;
  const auto readTime = systemTime(SYSTEM_TIME_MONOTONIC);
  if (env->IsInstanceOf(event, keyClass)) {
    auto key = android_view_KeyEvent_obtainAsCopy(env, event);
    // Wayland's zero repeat rate must also work when the pinned aconfig default
    // disables the optional Android user-setting branch in notifyKey.
    const uint32_t policyFlags = POLICY_FLAG_TRUSTED |
        (owner->keyRepeatEnabled.load() && repeatable ? 0 : POLICY_FLAG_DISABLE_KEY_REPEAT);
    owner->dispatcher.notifyKey(NotifyKeyArgs{
        key.getId(), key.getEventTime(), readTime, key.getDeviceId(),
        key.getSource(), key.getDisplayId(), policyFlags,
        key.getAction(), key.getFlags(), key.getKeyCode(), key.getScanCode(),
        key.getMetaState(), key.getDownTime()});
    return;
  }
  if (env->IsInstanceOf(event, motionClass)) {
    auto motion = android_view_MotionEvent_getNativePtr(env, event);
    if (!motion) {
      jniThrowException(env, "java/lang/IllegalArgumentException",
                        "MotionEvent is recycled");
      return;
    }
    std::vector<PointerCoords> coords(motion->getPointerCount());
    for (size_t sample = 0; sample <= motion->getHistorySize(); ++sample) {
      for (size_t pointer = 0; pointer < coords.size(); ++pointer) {
        coords[pointer] = MotionEvent::calculateTransformedCoords(
            motion->getSource(), motion->getFlags(), motion->getTransform(),
            *motion->getHistoricalRawPointerCoords(pointer, sample));
      }
      owner->dispatcher.notifyMotion(
          NotifyMotionArgs{motion->getId(),
                           motion->getSampleEventTimes()[sample],
                           readTime,
                           motion->getDeviceId(),
                           motion->getSource(),
                           motion->getDisplayId(),
                           POLICY_FLAG_TRUSTED,
                           motion->getAction(),
                           motion->getActionButton(),
                           motion->getFlags(),
                           motion->getMetaState(),
                           motion->getButtonState(),
                           motion->getClassification(),
                           motion->getEdgeFlags(),
                           static_cast<uint32_t>(coords.size()),
                           motion->getPointerProperties(),
                           coords.data(),
                           motion->getXPrecision(),
                           motion->getYPrecision(),
                           motion->getXCursorPosition(),
                           motion->getYCursorPosition(),
                           motion->getDownTime(),
                           {}});
    }
    return;
  }
  jniThrowException(env, "java/lang/IllegalArgumentException",
                    "Expected KeyEvent or MotionEvent");
}
static void connect(JNIEnv *env, jclass, jlong handle) {
  auto owner = get(env, handle);
  if (!owner)
    return;
  std::lock_guard lock(owner->connectionMutex);
  if (owner->closed) {
    status(env, DEAD_OBJECT, "connectSurfaceFlinger");
    return;
  }
  if (owner->client)
    return;
  auto client = sp<SurfaceComposerClient>::make();
  auto result = client->initCheck();
  if (result != OK) {
    status(env, result, "connectSurfaceFlinger");
    return;
  }
  auto listener = sp<WindowListener>::make(owner);
  result = client->addWindowInfosListener(listener);
  if (result != OK) {
    status(env, result, "addWindowInfosListener");
    return;
  }
  auto focus = sp<FocusService>::make(owner);
  result = defaultServiceManager()->addService(String16("inputflinger"), focus);
  if (result != OK) {
    client->removeWindowInfosListener(listener);
    status(env, result, "publishInputFlinger");
    return;
  }
  owner->client = client;
  owner->listener = listener;
  owner->focus = focus;
}

int registerNatives(JNIEnv *env) {
  env->GetJavaVM(&vm);
  keyClass = static_cast<jclass>(
      env->NewGlobalRef(FindClassOrDie(env, "android/view/KeyEvent")));
  motionClass = static_cast<jclass>(
      env->NewGlobalRef(FindClassOrDie(env, "android/view/MotionEvent")));
  jclass callbacks =
      FindClassOrDie(env, "dev/artzig/window/HostInputDispatcher$Callbacks");
#define METHOD(field, name, signature)                                         \
  methods.field = GetMethodIDOrDie(env, callbacks, name, signature)
  METHOD(noFocus, "notifyNoFocusedWindowAnr",
         "(Landroid/view/InputApplicationHandle;)V");
  METHOD(unresponsive, "notifyWindowUnresponsive",
         "(Landroid/os/IBinder;ILjava/lang/String;)V");
  METHOD(responsive, "notifyWindowResponsive", "(Landroid/os/IBinder;I)V");
  METHOD(broken, "notifyInputChannelBroken", "(Landroid/os/IBinder;)V");
  METHOD(focus, "notifyFocusChanged",
         "(Landroid/os/IBinder;Landroid/os/IBinder;)V");
  METHOD(display, "notifyFocusedDisplayChanged", "(I)V");
  METHOD(filter, "filterInputEvent", "(Landroid/view/InputEvent;I)Z");
  METHOD(keyQueue, "interceptKeyBeforeQueueing", "(Landroid/view/KeyEvent;I)I");
  METHOD(motionQueue, "interceptMotionBeforeQueueing", "(IIIJI)I");
  METHOD(keyDispatch, "interceptKeyBeforeDispatching",
         "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)J");
  METHOD(
      unhandled, "dispatchUnhandledKey",
      "(Landroid/os/IBinder;Landroid/view/KeyEvent;I)Landroid/view/KeyEvent;");
  METHOD(activity, "pokeUserActivity", "(JII)V");
  METHOD(outside, "onPointerDownOutsideFocus", "(Landroid/os/IBinder;)V");
  METHOD(capture, "setPointerCapture", "(Landroid/os/IBinder;I)V");
  METHOD(drop, "notifyDropWindow", "(Landroid/os/IBinder;FF)V");
  METHOD(interaction, "notifyDeviceInteraction", "(IJ[I)V");
  METHOD(unsupported, "notifyUnsupportedHardwareEvent",
         "(Ljava/lang/String;)V");
#undef METHOD
  env->DeleteLocalRef(callbacks);
  static const JNINativeMethod natives[] = {
      {"nativeCreate", "(Ldev/artzig/window/HostInputDispatcher$Callbacks;)J",
       reinterpret_cast<void *>(create)},
      {"nativeClose", "(J)V", reinterpret_cast<void *>(close)},
      {"nativeCreateInputChannel",
       "(JLjava/lang/String;)Landroid/view/InputChannel;",
       reinterpret_cast<void *>(createChannel)},
      {"nativeCreateInputMonitor",
       "(JLjava/lang/String;II)Landroid/view/InputChannel;",
       reinterpret_cast<void *>(createMonitor)},
      {"nativeDisplayRemoved", "(JI)V",
       reinterpret_cast<void *>(displayRemoved)},
      {"nativeRemoveInputChannel", "(JLandroid/os/IBinder;)V",
       reinterpret_cast<void *>(removeChannel)},
      {"nativeIsPointerInWindow", "(JLandroid/os/IBinder;III)Z",
       reinterpret_cast<void *>(pointerInWindow)},
      {"nativeSetFocusedApplication",
       "(JILandroid/view/InputApplicationHandle;)V",
       reinterpret_cast<void *>(focusedApplication)},
      {"nativeSetDesktopKeyboardFocus", "(JZ)V",
       reinterpret_cast<void *>(desktopKeyboardFocus)},
      {"nativeSetFocusedDisplay", "(JI)V",
       reinterpret_cast<void *>(focusedDisplay)},
      {"nativeSetInputDispatchMode", "(JZZ)V",
       reinterpret_cast<void *>(dispatchMode)},
      {"nativeSetKeyRepeatConfiguration", "(JIIZ)V",
       reinterpret_cast<void *>(keyRepeatConfiguration)},
      {"nativeSetInputFilterEnabled", "(JZ)V",
       reinterpret_cast<void *>(filterEnabled)},
      {"nativeSetInTouchMode", "(JZIIZI)Z",
       reinterpret_cast<void *>(touchMode)},
      {"nativeTransferTouch", "(JLandroid/os/IBinder;Landroid/os/IBinder;ZZ)Z",
       reinterpret_cast<void *>(transfer)},
      {"nativeTransferTouchOnDisplay", "(JLandroid/os/IBinder;I)Z",
       reinterpret_cast<void *>(transferDisplay)},
      {"nativePilferPointers", "(JLandroid/os/IBinder;)V",
       reinterpret_cast<void *>(pilfer)},
      {"nativeRequestPointerCapture", "(JLandroid/os/IBinder;Z)V",
       reinterpret_cast<void *>(capture)},
      {"nativeNotifyPointerCaptureChanged", "(JLandroid/os/IBinder;I)V",
       reinterpret_cast<void *>(captureChanged)},
      {"nativeSetDisplayEligibilityForPointerCapture", "(JIZ)V",
       reinterpret_cast<void *>(eligibility)},
      {"nativeInjectInputEvent", "(JLandroid/view/InputEvent;IIII)I",
       reinterpret_cast<void *>(inject)},
      {"nativeNotifyInputEvent", "(JLandroid/view/InputEvent;Z)V",
       reinterpret_cast<void *>(notifyEvent)},
      {"nativeConnectSurfaceFlinger", "(J)V",
       reinterpret_cast<void *>(connect)},
  };
  return jniRegisterNativeMethods(env, "dev/artzig/window/HostInputDispatcher",
                                  natives, std::size(natives));
}
} // namespace android::hostinput
int azk_register_HostInputDispatcher(JNIEnv *env) {
  return android::hostinput::registerNatives(env);
}
