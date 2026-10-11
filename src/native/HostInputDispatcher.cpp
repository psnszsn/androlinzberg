// The session's native desktop input integration around stock inputflinger.
//
// Stock InputManagerService owns InputManager: the reader over HostEventHub,
// the dispatcher and their policy. This adapter supplies only what a desktop
// seat adds below Java:
//  - the Wayland seat's devices and raw input_event values, fed to HostEventHub
//    (the reader's device source, src/native/input/HostEventHub.cpp);
//  - the task owning each SurfaceFlinger input window, which the dispatcher's
//    hosted drag targeting consults (frameworks-native patches 0008-0010);
//  - native drag targeting and handoff on the dispatcher InputManager created.
#define LOG_TAG "HostInputDispatcher"
#include <InputDispatcher.h>
#include <android_runtime/AndroidRuntime.h>
#include "android_util_Binder.h"
#include <gui/WindowInfosUpdate.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedUtfChars.h>
#include "core_jni_helpers.h"
#include "input/HostEventHub.h"
#include <functional>
#include <map>
#include <mutex>

namespace android {
inputdispatcher::InputDispatcher* hostInputDispatcher();
void setHostPointerCaptureObserver(std::function<void(const PointerCaptureRequest&)> observer);
void setHostWindowTaskResolver(
    std::function<inputdispatcher::InputDispatcher::HostedWindowTasks(const gui::WindowInfosUpdate&)> resolver);
}

namespace android::hostinput {
using inputdispatcher::InputDispatcher;
static JavaVM *vm;
static jclass binderClass;
static jmethodID windowTasksMethod;
static jmethodID pointerCaptureMethod;

// SurfaceFlinger invokes the listener on a Binder thread. Attach only for the
// callback and journal Java exceptions before clearing them, as stock
// NativeInputManager does for asynchronous callbacks.
struct Callback {
  JNIEnv *env = nullptr;
  bool attached = false;
  Callback() {
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED)
      attached = vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK;
    LOG_ALWAYS_FATAL_IF(!env, "Cannot attach input callback to ART");
    LOG_ALWAYS_FATAL_IF(env->PushLocalFrame(32) != JNI_OK, "Input callback local frame failed");
  }
  bool failed() {
    if (!env->ExceptionCheck()) return false;
    jniLogException(env, ANDROID_LOG_ERROR, LOG_TAG, nullptr);
    env->ExceptionClear();
    return true;
  }
  ~Callback() {
    failed();
    env->PopLocalFrame(nullptr);
    if (attached) vm->DetachCurrentThread();
  }
};

// The task owning each input window, from the system server's task map.
static InputDispatcher::HostedWindowTasks windowTasks(jobject callbacks, const gui::WindowInfosUpdate &update) {
  InputDispatcher::HostedWindowTasks result;
  std::vector<sp<IBinder>> tokens;
  for (const auto &info : update.windowInfos)
    if (info.token) tokens.push_back(info.token);
  if (tokens.empty()) return result;
  Callback c;
  ScopedLocalRef<jobjectArray> array(c.env, c.env->NewObjectArray(tokens.size(), binderClass, nullptr));
  if (c.failed()) return result;
  for (size_t i = 0; i < tokens.size(); i++)
    c.env->SetObjectArrayElement(array.get(), i, javaObjectForIBinder(c.env, tokens[i]));
  ScopedLocalRef<jobjectArray> tasks(c.env, static_cast<jobjectArray>(
      c.env->CallObjectMethod(callbacks, windowTasksMethod, array.get())));
  if (c.failed() || tasks.get() == nullptr || c.env->GetArrayLength(tasks.get()) != jsize(tokens.size()))
    return result;
  for (size_t i = 0; i < tokens.size(); i++) {
    ScopedLocalRef<jobject> value(c.env, c.env->GetObjectArrayElement(tasks.get(), i));
    if (value.get() == nullptr) continue;
    auto task = ibinderForJavaObject(c.env, value.get());
    if (task) result.emplace(tokens[i], task);
  }
  return result;
}

struct State {
  jobject callbacks; // global reference
  void close(JNIEnv *env) {
    if (!callbacks) return;
    // Waits for an update in progress; later updates carry no tasks.
    setHostWindowTaskResolver(nullptr);
    setHostPointerCaptureObserver(nullptr);
    env->DeleteGlobalRef(callbacks);
    callbacks = nullptr;
  }
};

static std::mutex registryMutex;
static std::map<jlong, std::unique_ptr<State>> registry;
static jlong nextHandle = 1;

static InputDispatcher *dispatcherOrThrow(JNIEnv *env) {
  InputDispatcher *dispatcher = hostInputDispatcher();
  if (dispatcher == nullptr)
    jniThrowException(env, "java/lang/IllegalStateException", "InputManagerService has not started");
  return dispatcher;
}

static std::shared_ptr<HostEventHub> hubOrThrow(JNIEnv *env) {
  auto hub = HostEventHub::instance();
  if (!hub)
    jniThrowException(env, "java/lang/IllegalStateException", "InputManagerService has not started");
  return hub;
}

static jlong create(JNIEnv *env, jclass, jobject callbacks) {
  if (!dispatcherOrThrow(env)) return 0;
  auto state = std::make_unique<State>();
  state->callbacks = env->NewGlobalRef(callbacks);
  jobject observer = state->callbacks;
  setHostWindowTaskResolver([observer](const gui::WindowInfosUpdate &update) {
    return windowTasks(observer, update);
  });
  // The reader's capture requests go to the seat, which locks the compositor's pointer.
  setHostPointerCaptureObserver([observer](const PointerCaptureRequest &request) {
    Callback c;
    c.env->CallVoidMethod(observer, pointerCaptureMethod, javaObjectForIBinder(c.env, request.window),
                          static_cast<jint>(request.seq));
  });
  std::lock_guard lock(registryMutex);
  const jlong handle = nextHandle++;
  registry.emplace(handle, std::move(state));
  return handle;
}

static void close(JNIEnv *env, jclass, jlong handle) {
  std::unique_ptr<State> state;
  {
    std::lock_guard lock(registryMutex);
    auto node = registry.extract(handle);
    if (node.empty()) return;
    state = std::move(node.mapped());
  }
  state->close(env);
}

static jobject hostedDragTarget(JNIEnv *env, jclass, jobject task, jint display, jfloat x, jfloat y,
                                jfloatArray local) {
  if (!local || env->GetArrayLength(local) != 2) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Drag coordinates require two floats");
    return nullptr;
  }
  InputDispatcher *dispatcher = dispatcherOrThrow(env);
  if (!dispatcher) return nullptr;
  auto target = dispatcher->hostedDragTarget(ibinderForJavaObject(env, task), ui::LogicalDisplayId{display}, x, y);
  const jfloat point[] = {target.x, target.y};
  env->SetFloatArrayRegion(local, 0, 2, point);
  auto object = javaObjectForIBinder(env, target.token);
  // The result must outlive this snapshot's last native token reference.
  return object && env->GetObjectRefType(object) == JNIGlobalRefType ? env->NewLocalRef(object) : object;
}

static jboolean handoffDrag(JNIEnv *env, jclass, jobject inputToken) {
  InputDispatcher *dispatcher = dispatcherOrThrow(env);
  return dispatcher && dispatcher->handoffHostedDrag(ibinderForJavaObject(env, inputToken));
}

// ----- Seat devices (HostEventHub)

static jint addDevice(JNIEnv *env, jclass, jint kind, jstring name, jint width, jint height, jstring keyCharacterMap,
                      jint axes) {
  auto hub = hubOrThrow(env);
  if (!hub) return -1;
  if (kind < 0 || kind > int(HostDeviceSpec::Kind::STYLUS)) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Unknown seat device kind");
    return -1;
  }
  HostDeviceSpec spec;
  spec.kind = HostDeviceSpec::Kind(kind);
  spec.name = ScopedUtfChars(env, name).c_str();
  spec.width = width;
  spec.height = height;
  if (keyCharacterMap) spec.keyCharacterMap = ScopedUtfChars(env, keyCharacterMap).c_str();
  spec.axes = axes;
  return hub->addDevice(spec);
}

static void removeDevice(JNIEnv *env, jclass, jint deviceId) {
  if (auto hub = hubOrThrow(env)) hub->removeDevice(deviceId);
}

static jboolean setKeyCharacterMap(JNIEnv *env, jclass, jint deviceId, jstring contents) {
  auto hub = hubOrThrow(env);
  return hub && hub->setKeyCharacterMap(deviceId, ScopedUtfChars(env, contents).c_str());
}

/** events holds (type, code, value) triples; SYN_REPORT entries end packets. */
static void events(JNIEnv *env, jclass, jint deviceId, jlong whenNanos, jintArray values) {
  auto hub = hubOrThrow(env);
  if (!hub) return;
  ScopedIntArrayRO array(env, values);
  if (array.size() % 3 != 0) {
    jniThrowException(env, "java/lang/IllegalArgumentException", "Seat events are (type, code, value) triples");
    return;
  }
  for (size_t i = 0; i < array.size(); i += 3)
    hub->event(deviceId, whenNanos, array[i], array[i + 1], array[i + 2]);
}

int registerNatives(JNIEnv *env) {
  env->GetJavaVM(&vm);
  binderClass = static_cast<jclass>(env->NewGlobalRef(FindClassOrDie(env, "android/os/IBinder")));
  jclass callbacks = FindClassOrDie(env, "dev/artzig/window/HostInputDispatcher$Callbacks");
  windowTasksMethod = GetMethodIDOrDie(env, callbacks, "inputWindowTasks",
                                       "([Landroid/os/IBinder;)[Landroid/os/IBinder;");
  pointerCaptureMethod = GetMethodIDOrDie(env, callbacks, "pointerCapture", "(Landroid/os/IBinder;I)V");
  env->DeleteLocalRef(callbacks);
  static const JNINativeMethod natives[] = {
      {"nativeCreate", "(Ldev/artzig/window/HostInputDispatcher$Callbacks;)J", reinterpret_cast<void *>(create)},
      {"nativeClose", "(J)V", reinterpret_cast<void *>(close)},
      {"nativeHostedDragTarget", "(Landroid/os/IBinder;IFF[F)Landroid/os/IBinder;", reinterpret_cast<void *>(hostedDragTarget)},
      {"nativeHandoffDrag", "(Landroid/os/IBinder;)Z", reinterpret_cast<void *>(handoffDrag)},
      {"nativeAddDevice", "(ILjava/lang/String;IILjava/lang/String;I)I", reinterpret_cast<void *>(addDevice)},
      {"nativeRemoveDevice", "(I)V", reinterpret_cast<void *>(removeDevice)},
      {"nativeSetKeyCharacterMap", "(ILjava/lang/String;)Z", reinterpret_cast<void *>(setKeyCharacterMap)},
      {"nativeEvents", "(IJ[I)V", reinterpret_cast<void *>(events)},
  };
  return jniRegisterNativeMethods(env, "dev/artzig/window/HostInputDispatcher", natives, std::size(natives));
}
} // namespace android::hostinput

int azk_register_HostInputDispatcher(JNIEnv *env) {
  return android::hostinput::registerNatives(env);
}
