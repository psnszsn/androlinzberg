#include "android_media_MediaMetricsJNI.h"
#include "MediaExtractorService.h"
#include <binder/PermissionCache.h>
namespace android { jobject javaObjectForIBinder(JNIEnv*, const sp<IBinder>&); }
#include "com_android_internal_os_Zygote.h"
#include <android-base/stringprintf.h>
#include <nativehelper/ScopedUtfChars.h>
#include "android_os_HidlMemory.h"
#include "android_os_NativeHandle.h"
// AOSP C++ ABI bridge: register the framework/HWUI JNI natives with ART,
// mirroring libs/hwui/apex/jni_runtime.cpp (layoutlib's entry) minus the classes
// we don't ship yet (Movie and PDF).
#include <GraphicsJNI.h>
#include <Bitmap.h>
#include <jni/Bitmap.h>
#include <input/KeyCharacterMap.h>
#include <SkGraphics.h>
#include <jni.h>
#include <Properties.h>
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/JNIHelp.h>
#include <system/audio.h>
#include <android/binder_ibinder_jni.h>
#include <binder/IBinder.h>
#include <binder/Parcel.h>
#include <binder/Status.h>
#include <camera/VendorTagDescriptor.h>
#include <nativehelper/ScopedLocalRef.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <unistd.h>

// Pinned AudioSystem JNI capability results and device constants. Keep their
// definitions in the original native audio headers; the hosted JNI lives in Zig.
extern "C" int azk_audio_framework_capability(int key) {
    switch (key) {
        case 0: return FCC_LIMIT;
        case 1: return SAMPLE_RATE_HZ_MAX;
        case 2: return SAMPLE_RATE_HZ_MIN;
        case 3: return AUDIO_DEVICE_OUT_SPEAKER;
        case 4: return AUDIO_CHANNEL_OUT_MONO;
        case 5: return AUDIO_CHANNEL_OUT_STEREO;
        default: return -1;
    }
}

// The exit smoke must fail deterministically if ART's exit hook runs libc
// destructors while Java/native worker threads are still active.
static void installLibcExitTrap(JNIEnv*, jclass) {
    if (std::atexit([] { ::_exit(99); }) != 0) ::_exit(98);
}

// Pinned AndroidRuntime::registerNativeMethods. GLES JNI uses this C++ entry;
// the full AndroidRuntime owns a separate VM and cannot be linked into this host.
int android::AndroidRuntime::registerNativeMethods(JNIEnv* env, const char* className,
        const JNINativeMethod* gMethods, int numMethods) {
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

// Pinned android_util_Binder::binder_report_exception. The main Binder JNI is hosted
// separately, so resolve its Java collaborators here instead of using that TU's globals.
namespace android {
void binder_report_exception(JNIEnv* env, jthrowable exception, const char* message) {
    env->ExceptionClear();
    ScopedLocalRef<jclass> log(env, env->FindClass("android/util/Log"));
    ScopedLocalRef<jstring> tag(env, env->NewStringUTF("JavaBinder"));
    ScopedLocalRef<jstring> text(env, env->NewStringUTF(message));
    if (log != nullptr && tag != nullptr && text != nullptr) {
        jmethodID report = env->GetStaticMethodID(log.get(), "e",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)I");
        if (report != nullptr) env->CallStaticIntMethod(log.get(), report, tag.get(), text.get(), exception);
    }
    env->ExceptionClear();
    ScopedLocalRef<jclass> error(env, env->FindClass("java/lang/Error"));
    if (error == nullptr) { env->ExceptionClear(); return; }
    if (!env->IsInstanceOf(exception, error.get())) return;
    ScopedLocalRef<jclass> threadClass(env, env->FindClass("java/lang/Thread"));
    if (threadClass != nullptr) {
        jmethodID current = env->GetStaticMethodID(threadClass.get(), "currentThread", "()Ljava/lang/Thread;");
        jmethodID dispatch = env->GetMethodID(threadClass.get(), "dispatchUncaughtException", "(Ljava/lang/Throwable;)V");
        if (current != nullptr && dispatch != nullptr) {
            ScopedLocalRef<jobject> thread(env, env->CallStaticObjectMethod(threadClass.get(), current));
            if (thread != nullptr) env->CallVoidMethod(thread.get(), dispatch, exception);
        }
    }
    env->ExceptionClear();
    env->Throw(exception);
    env->ExceptionDescribe();
    env->FatalError("java.lang.Error thrown during hwbinder transaction");
}
} // namespace android

namespace android {
int register_android_os_HidlSupport(JNIEnv*);
int register_android_os_HwBinder(JNIEnv*);
int register_android_os_HwRemoteBinder(JNIEnv*);
int register_android_os_HwBlob(JNIEnv*);
int register_android_os_HwParcel(JNIEnv*);
int register_android_media_MediaMetrics(JNIEnv*);
int register_com_android_net_module_util_BpfMap(JNIEnv*, const char*);
}
// The pinned legacy DRM JNI owns its complete registration table. Rename its
// standalone JNI_OnLoad at compile time so it can share the native island.
extern "C" jint azk_legacy_drm_JNI_OnLoad(JavaVM*, void*);
static int registerLegacyDrm(JNIEnv* env) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return -1;
    return azk_legacy_drm_JNI_OnLoad(vm, nullptr) == JNI_VERSION_1_4 ? 0 : -1;
}

static int registerBpfMap(JNIEnv* env) {
    return android::register_com_android_net_module_util_BpfMap(env,
            "com/android/net/module/util/BpfMap");
}

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_BitmapRegionDecoder(JNIEnv*);
extern int register_android_graphics_RuntimeXfermode(JNIEnv*);
extern int register_android_graphics_Camera(JNIEnv*);
extern int register_android_hardware_camera2_CameraMetadata(JNIEnv*);
extern int register_android_media_MediaHTTPConnection(JNIEnv*);
extern int register_android_media_MediaExtractor(JNIEnv*);
extern int register_android_media_Descrambler(JNIEnv*);

namespace {
jobject nativeBundle(JNIEnv* env, jobject) {
    android::os::PersistableBundle value;
    value.putInt(android::String16("gain"), -2);
    value.putString(android::String16("client"), android::String16("native focus"));
    value.putLongVector(android::String16("times"), {17, 1LL << 40});
    android::os::PersistableBundle nested;
    nested.putBoolean(android::String16("active"), true);
    value.putPersistableBundle(android::String16("nested"), nested);
    return android::MediaMetricsJNI::nativeToJavaPersistableBundle(env, &value);
}
}

// Bootstrap only: the service implementation and generated Binder protocol are AOSP.
static jobject nativeCreateMediaExtractorService(JNIEnv* env, jobject) {
    return android::javaObjectForIBinder(env, android::sp<android::MediaExtractorService>::make());
}
extern "C" int azk_register_media_extractor_service(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativeCreateMediaExtractorService", "()Landroid/os/IBinder;", reinterpret_cast<void*>(nativeCreateMediaExtractorService)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/system/SystemServices", methods, 1);
}

static jboolean nativePermission(JNIEnv* env, jobject, jstring permission, jint pid, jint uid) {
    ScopedUtfChars name(env, permission);
    if (!name.c_str()) return JNI_FALSE;
    return android::PermissionCache::checkPermission(android::String16(name.c_str()), pid, uid);
}
extern "C" int azk_register_audio_manager_smoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativePermission", "(Ljava/lang/String;II)Z", reinterpret_cast<void*>(nativePermission)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockAudioManagerSmoke", methods, 1);
}

extern "C" int azk_register_media_metrics_smoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativeBundle", "()Landroid/os/PersistableBundle;", reinterpret_cast<void*>(nativeBundle)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockMediaMetricsSmoke", methods, 1);
}

extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv*);
extern int register_android_graphics_Graphics(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv*);
extern int register_android_graphics_MaskFilter(JNIEnv*);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv*);
extern int register_android_graphics_Shader(JNIEnv*);
extern int register_android_graphics_RenderEffect(JNIEnv*);
extern int register_android_graphics_Typeface(JNIEnv*);
extern int register_android_hardware_SyncFence(JNIEnv*);
extern int register_android_media_PublicFormatUtils(JNIEnv*);
extern int register_android_opengl_jni_EGL15(JNIEnv*);
extern "C" int azk_register_legacy_gles_backend(JNIEnv*);
extern int register_com_google_android_gles_jni_GLImpl(JNIEnv*);
extern int register_android_opengl_jni_GLES20(JNIEnv*);
extern int register_android_opengl_jni_GLES30(JNIEnv*);
extern int register_android_opengl_jni_GLES31(JNIEnv*);
extern int register_android_opengl_jni_GLES32(JNIEnv*);

namespace android {
extern int register_android_graphics_Canvas(JNIEnv*);
extern int register_android_graphics_CanvasProperty(JNIEnv*);
extern int register_android_graphics_ColorFilter(JNIEnv*);
extern int register_android_graphics_Color(JNIEnv*);
extern int register_android_graphics_ColorSpace(JNIEnv*);
extern int register_android_graphics_DrawFilter(JNIEnv*);
extern int register_android_graphics_FontFamily(JNIEnv*);
extern int register_android_graphics_Gainmap(JNIEnv*);
extern int register_android_graphics_Matrix(JNIEnv*);
extern int register_android_graphics_Paint(JNIEnv*);
extern int register_android_graphics_Path(JNIEnv*);
extern int register_android_graphics_PathIterator(JNIEnv*);
extern int register_android_graphics_PathMeasure(JNIEnv*);
extern int register_android_graphics_Picture(JNIEnv*);
extern int register_android_graphics_Region(JNIEnv*);
extern int register_android_graphics_fonts_Font(JNIEnv*);
extern int register_android_graphics_fonts_FontFamily(JNIEnv*);
extern int register_android_graphics_text_MeasuredText(JNIEnv*);
extern int register_android_graphics_text_LineBreaker(JNIEnv*);
extern int register_android_graphics_text_TextShaper(JNIEnv*);
extern int register_android_graphics_text_GraphemeBreak(JNIEnv*);
extern int register_android_graphics_MeshSpecification(JNIEnv*);
extern int register_android_graphics_Mesh(JNIEnv*);
extern int register_android_util_PathParser(JNIEnv*);
}  // namespace android

namespace android {
extern jobject android_view_KeyCharacterMap_create(JNIEnv*, int32_t,
                                                   std::unique_ptr<KeyCharacterMap>);
extern int register_android_content_AssetManager(JNIEnv*);
extern int register_android_content_res_ApkAssets(JNIEnv*);
extern int register_android_content_XmlBlock(JNIEnv*);
extern int register_android_content_StringBlock(JNIEnv*);
extern int register_android_view_RenderNode(JNIEnv*);
extern int register_android_view_MotionEvent(JNIEnv*);
extern int register_android_view_KeyEvent(JNIEnv*);
extern int register_android_view_InputDevice(JNIEnv*);
extern int register_android_view_VelocityTracker(JNIEnv*);
extern int register_android_view_KeyCharacterMap(JNIEnv*);
extern int register_android_animation_PropertyValuesHolder(JNIEnv*);
extern int register_android_view_DisplayListCanvas(JNIEnv*);
extern int register_android_view_ThreadedRenderer(JNIEnv*);
extern int register_android_graphics_HardwareRendererObserver(JNIEnv*);
extern int register_android_graphics_HardwareBufferRenderer(JNIEnv*);
extern int register_android_graphics_animation_NativeInterpolatorFactory(JNIEnv*);
extern int register_android_graphics_animation_RenderNodeAnimator(JNIEnv*);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv*);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv*);
extern int register_android_backup_BackupDataInput(JNIEnv*);
extern int register_android_backup_BackupDataOutput(JNIEnv*);
extern int register_com_android_internal_os_ApplicationSharedMemory(JNIEnv*);
extern int register_com_android_internal_content_NativeLibraryHelper(JNIEnv*);
extern int register_android_os_SELinux(JNIEnv*);
extern int register_android_os_NativeHandle(JNIEnv*);
extern int register_android_os_FileObserver(JNIEnv*);
extern jlong com_android_internal_os_ZygoteCommandBuffer_getNativeBuffer(JNIEnv*, jclass, jint);
extern void com_android_internal_os_ZygoteCommandBuffer_freeNativeBuffer(JNIEnv*, jclass, jlong);
extern void com_android_internal_os_ZygoteCommandBuffer_insert(JNIEnv*, jclass, jlong, jstring);
extern jstring com_android_internal_os_ZygoteCommandBuffer_nativeNextArg(JNIEnv*, jclass, jlong);
extern void com_android_internal_os_ZygoteCommandBuffer_nativeReadFullyAndReset(JNIEnv*, jclass, jlong);
extern jint com_android_internal_os_ZygoteCommandBuffer_nativeGetCount(JNIEnv*, jclass, jlong);
extern int register_android_os_PerfettoTrace(JNIEnv*);
extern void android_os_PerfettoTrace_register(bool);
extern int register_android_os_PerfettoTrackEventExtra(JNIEnv*);
extern int register_android_util_jar_StrictJarFile(JNIEnv*);
extern int register_android_util_MemoryIntArray(JNIEnv*);
extern int register_com_android_internal_security_VerityUtils(JNIEnv*);
extern int register_android_os_incremental_IncrementalManager(JNIEnv*);
extern int register_android_net_LocalSocketImpl(JNIEnv*);
extern int register_android_app_backup_FullBackup(JNIEnv*);
extern int register_android_database_CursorWindow(JNIEnv*);
extern int register_android_database_SQLiteConnection(JNIEnv*);
extern int register_android_database_SQLiteGlobal(JNIEnv*);
extern int register_android_database_SQLiteDebug(JNIEnv*);
extern int register_android_database_SQLiteRawStatement(JNIEnv*);
}  // namespace android
namespace android {
namespace azk_dummy_reopen {}
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv*);
}  // namespace android
extern int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv*);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern "C" int azk_register_hardware_buffer(JNIEnv*);
extern "C" int azk_register_graphics_environment_backend(JNIEnv*);
extern int register_dev_artzig_StockFenceSmoke(JNIEnv*);
namespace android { int register_android_os_GraphicsEnvironment(JNIEnv*); }
extern int register_android_hardware_UsbDevice(JNIEnv*);
extern int register_android_hardware_UsbDeviceConnection(JNIEnv*);
extern int register_android_hardware_UsbRequest(JNIEnv*);
extern int register_android_hardware_OverlayProperties(JNIEnv*);

// Pinned PerfettoTrace.native_register is @FastNative, but its C++ registration
// points at a CriticalNative-style bool-only entry. Keep the stock Java and SDK;
// supply the implicit JNI arguments here so the requested backend reaches it.
static void registerPerfettoBackend(JNIEnv*, jclass, jboolean inProcess) {
    android::android_os_PerfettoTrace_register(inProcess == JNI_TRUE);
}

static int registerPerfettoTrace(JNIEnv* env) {
    const int result = android::register_android_os_PerfettoTrace(env);
    if (result != 0) return result;
    const JNINativeMethod methods[] = {
        {"native_register", "(Z)V", reinterpret_cast<void*>(registerPerfettoBackend)},
    };
    return jniRegisterNativeMethods(env, "android/os/PerfettoTrace", methods, 1);
}

static jint azk_Bitmap_nativeConfig(JNIEnv*, jclass, jlong bitmap_handle) {
    android::Bitmap& bitmap = android::bitmap::toBitmap(bitmap_handle);
    if (bitmap.isHardware()) {
        return GraphicsJNI::kHardware_LegacyBitmapConfig;
    }

    // Skia's native N32 color type aliases one of RGBA_8888/BGRA_8888.
    // Android's mapper handles that alias, but host-created bitmaps can use
    // the other byte order. Both are Bitmap.Config.ARGB_8888 at the Java API.
    const SkColorType color_type = bitmap.info().colorType();
    if (color_type == kRGBA_8888_SkColorType || color_type == kBGRA_8888_SkColorType) {
        return GraphicsJNI::kARGB_8888_LegacyBitmapConfig;
    }
    return GraphicsJNI::colorTypeToLegacyBitmapConfig(color_type);
}

// Test the original JNI value conversion without requiring a hardware service.
static jobject roundTripHidlMemory(JNIEnv* env, jclass, jobject memory) {
    const auto* native = android::JHidlMemory::fromJava(env, memory);
    if (env->ExceptionCheck() || native == nullptr) return nullptr;
    return android::JHidlMemory::toJava(env, *native);
}

static int registerStockHidlMemorySmoke(JNIEnv* env) {
    const JNINativeMethod methods[] = {
        {"nativeRoundTrip", "(Landroid/os/HidlMemory;)Landroid/os/HidlMemory;",
         reinterpret_cast<void*>(roundTripHidlMemory)},
    };
    return jniRegisterNativeMethods(env, "dev/artzig/StockHidlMemorySmoke", methods, 1);
}

// Reuse ZygoteCommandBuffer's original parser and allocation ownership. The full
// Zygote.cpp requires Android process specialization, mounts and seccomp. Its
// fatal-error formatter is reused verbatim here; process creation is a host seam.
namespace android {
using android::base::StringPrintf;
[[noreturn]]
void zygote::ZygoteFailure(JNIEnv* env,
                           const char* process_name,
                           jstring managed_process_name,
                           const std::string& msg) {
  std::unique_ptr<ScopedUtfChars> scoped_managed_process_name_ptr = nullptr;
  if (managed_process_name != nullptr) {
    scoped_managed_process_name_ptr.reset(new ScopedUtfChars(env, managed_process_name));
    if (scoped_managed_process_name_ptr->c_str() != nullptr) {
      process_name = scoped_managed_process_name_ptr->c_str();
    }
  }

  const std::string& error_msg =
      (process_name == nullptr || process_name[0] == '\0') ?
      msg : StringPrintf("(%s) %s", process_name, msg.c_str());

  env->FatalError(error_msg.c_str());
  __builtin_unreachable();
}

}  // namespace android

static jboolean unsupportedZygoteFork(JNIEnv* env, jclass, jlong, jint, jint, jint, jstring) {
    ScopedLocalRef<jclass> diagnostics(env, env->FindClass("dev/artzig/ArtzigTestDiagnostics"));
    if (diagnostics.get() == nullptr) return JNI_FALSE;
    jmethodID unsupported = env->GetStaticMethodID(diagnostics.get(), "unsupported",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/UnsupportedOperationException;");
    if (unsupported == nullptr) return JNI_FALSE;
    ScopedLocalRef<jstring> owner(env, env->NewStringUTF("com.android.internal.os.ZygoteCommandBuffer"));
    ScopedLocalRef<jstring> member(env, env->NewStringUTF("nativeForkRepeatedly"));
    ScopedLocalRef<jstring> detail(env, env->NewStringUTF("Process creation requires a hosted zygote service"));
    if (env->ExceptionCheck()) return JNI_FALSE;
    jvalue args[] = {{.l = owner.get()}, {.l = member.get()}, {.l = detail.get()}};
    ScopedLocalRef<jthrowable> failure(env, static_cast<jthrowable>(
            env->CallStaticObjectMethodA(diagnostics.get(), unsupported, args)));
    if (failure.get() != nullptr) env->Throw(failure.get());
    return JNI_FALSE;
}

static int registerHostedZygoteCommandBuffer(JNIEnv* env) {
    // Select the six original parser bindings directly. The original fork loop
    // and its Android process/property dependencies are discarded by the linker.
#define ZYGOTE_PARSER_METHOD(name, signature) \
    {#name, signature, reinterpret_cast<void*>(android::com_android_internal_os_ZygoteCommandBuffer_##name)}
    const JNINativeMethod methods[] = {
        ZYGOTE_PARSER_METHOD(getNativeBuffer, "(I)J"),
        ZYGOTE_PARSER_METHOD(freeNativeBuffer, "(J)V"),
        ZYGOTE_PARSER_METHOD(insert, "(JLjava/lang/String;)V"),
        ZYGOTE_PARSER_METHOD(nativeNextArg, "(J)Ljava/lang/String;"),
        ZYGOTE_PARSER_METHOD(nativeReadFullyAndReset, "(J)V"),
        ZYGOTE_PARSER_METHOD(nativeGetCount, "(J)I"),
        {"nativeForkRepeatedly", "(JIIILjava/lang/String;)Z",
         reinterpret_cast<void*>(unsupportedZygoteFork)},
    };
#undef ZYGOTE_PARSER_METHOD
    return jniRegisterNativeMethods(env, "com/android/internal/os/ZygoteCommandBuffer", methods, 7);
}

static int azk_register_android_graphics_Bitmap(JNIEnv* env) {
    if (register_android_graphics_Bitmap(env) < 0) return -1;

    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    if (bitmap_class == nullptr) return -1;
    const JNINativeMethod methods[] = {
            {"nativeConfig", "(J)I", reinterpret_cast<void*>(azk_Bitmap_nativeConfig)},
    };
    return env->RegisterNatives(bitmap_class, methods, 1);
}

static jobject azk_HostedInputDispatcher_nativeLoadKeyCharacterMap(
        JNIEnv* env, jclass, jint device_id, jstring contents_object) {
    const char* contents = env->GetStringUTFChars(contents_object, nullptr);
    if (contents == nullptr) return nullptr;
    auto result = android::KeyCharacterMap::loadContents(
            "artzig-host.kcm", contents, android::KeyCharacterMap::Format::BASE);
    env->ReleaseStringUTFChars(contents_object, contents);
    if (!result.ok()) {
        jclass exception_class = env->FindClass("java/lang/IllegalStateException");
        if (exception_class != nullptr) {
            env->ThrowNew(exception_class, "Could not parse the hosted keyboard character map");
        }
        return nullptr;
    }
    return android::android_view_KeyCharacterMap_create(
            env, device_id, std::make_unique<android::KeyCharacterMap>(**result));
}

static int azk_register_android_view_HostedInputDispatcher(JNIEnv* env) {
    jclass dispatcher_class = env->FindClass("android/view/HostedInputDispatcher");
    if (dispatcher_class == nullptr) return -1;
    const JNINativeMethod methods[] = {
            {"nativeLoadKeyCharacterMap", "(ILjava/lang/String;)Landroid/view/KeyCharacterMap;",
             reinterpret_cast<void*>(azk_HostedInputDispatcher_nativeLoadKeyCharacterMap)},
    };
    return env->RegisterNatives(dispatcher_class, methods, 1);
}

// Minimal android::AndroidRuntime backing for core/jni code (getJNIEnv in
// ApkAssets callbacks). The VM is stored at registration time.
#include <android_runtime/AndroidRuntime.h>
static JavaVM* g_azk_vm = nullptr;
namespace android {
/*static*/ JavaVM* AndroidRuntime::getJavaVM() {
    return g_azk_vm;
}
/*static*/ JNIEnv* AndroidRuntime::getJNIEnv() {
    if (!g_azk_vm) return nullptr;
    JNIEnv* env = nullptr;
    if (g_azk_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return nullptr;
    return env;
}
}  // namespace android

extern "C" {

// Pinned AndroidRuntime::exit, non-zygote/non-coverage path. The host owns ART;
// libc cleanup can destroy native globals while its worker threads still run.
[[noreturn]] void azk_android_runtime_exit(int status) {
    ::_exit(status);
}

int azk_register_graphics_jni(JNIEnv* env) {
    env->GetJavaVM(&g_azk_vm);
    const JNINativeMethod exitSmokeMethods[] = {
        {"installLibcExitTrap", "()V", reinterpret_cast<void*>(installLibcExitTrap)},
    };
    if (jniRegisterNativeMethods(env, "dev/artzig/JvmExitSmoke", exitSmokeMethods, 1) != 0) {
        return -1;
    }
    // The platform normally does this from libhwui's JNI_OnLoad. artzig
    // registers the same natives explicitly, so initialize GraphicsJNI's VM
    // slot here as well for font-data finalizers running on GC threads.
    GraphicsJNI::setJavaVM(g_azk_vm);
    SkGraphics::Init();
    android::uirenderer::Properties::overrideRenderPipelineType(
            android::uirenderer::RenderPipelineType::SkiaGL);
    using Reg = int (*)(JNIEnv*);
    struct Entry {
        Reg fn;
        const char* name;
    };
#define E(f)  \
    {         \
        f, #f \
    }
    static const Entry entries[] = {
            E(register_android_hardware_SyncFence),
            E(register_android_media_PublicFormatUtils),
            E(register_android_opengl_jni_EGL15),
            E(azk_register_legacy_gles_backend),
            E(register_com_google_android_gles_jni_GLImpl),
            E(android::register_android_os_SELinux),
            E(android::register_android_os_NativeHandle),
            E(android::register_android_os_FileObserver),
            E(registerLegacyDrm),
            E(android::register_android_os_HidlMemory),
            E(android::register_android_os_HidlSupport),
            E(android::register_android_os_HwBinder),
            E(android::register_android_os_HwRemoteBinder),
            E(android::register_android_os_HwBlob),
            E(android::register_android_os_HwParcel),
            E(android::register_android_media_MediaMetrics),
            E(azk_register_media_metrics_smoke),
            E(azk_register_media_extractor_service),
            E(azk_register_audio_manager_smoke),
            E(registerStockHidlMemorySmoke),
            E(registerHostedZygoteCommandBuffer),
            E(registerPerfettoTrace),
            E(android::register_android_os_PerfettoTrackEventExtra),
            E(android::register_android_util_jar_StrictJarFile),
            E(android::register_android_util_MemoryIntArray),
            E(android::register_com_android_internal_security_VerityUtils),
            E(android::register_android_os_incremental_IncrementalManager),
            E(android::register_com_android_internal_content_NativeLibraryHelper),
            E(azk_register_hardware_buffer),
            E(azk_register_graphics_environment_backend),
            E(register_dev_artzig_StockFenceSmoke),
            E(android::register_android_os_GraphicsEnvironment),
            E(register_android_hardware_UsbDevice),
            E(register_android_hardware_UsbDeviceConnection),
            E(register_android_hardware_UsbRequest),
            E(register_android_hardware_OverlayProperties),
            E(android::register_android_graphics_Canvas),
            E(android::register_android_graphics_Color),
            E(android::register_android_graphics_ColorSpace),
            E(register_android_graphics_Graphics),
            E(azk_register_android_graphics_Bitmap),
            E(registerBpfMap),
            E(register_android_graphics_BitmapFactory),
            E(register_android_graphics_BitmapRegionDecoder),
            E(register_android_graphics_RuntimeXfermode),
            E(register_android_graphics_Camera),
            E(register_android_hardware_camera2_CameraMetadata),
            E(register_android_media_MediaHTTPConnection),
            E(register_android_media_MediaExtractor),
            E(register_android_media_Descrambler),
            E(register_android_graphics_CreateJavaOutputStreamAdaptor),
            E(android::register_android_graphics_CanvasProperty),
            E(android::register_android_graphics_ColorFilter),
            E(android::register_android_graphics_DrawFilter),
            E(android::register_android_graphics_FontFamily),
            E(android::register_android_graphics_Gainmap),
            E(register_android_graphics_Interpolator),
            E(register_android_graphics_MaskFilter),
            E(android::register_android_graphics_Matrix),
            E(register_android_graphics_NinePatch),
            E(android::register_android_graphics_Paint),
            E(android::register_android_graphics_Path),
            E(android::register_android_graphics_PathIterator),
            E(android::register_android_graphics_PathMeasure),
            E(register_android_graphics_PathEffect),
            E(android::register_android_graphics_Picture),
            E(android::register_android_graphics_Region),
            E(register_android_graphics_Shader),
            E(register_android_graphics_RenderEffect),
            E(register_android_graphics_Typeface),
            E(android::register_android_graphics_fonts_Font),
            E(android::register_android_graphics_fonts_FontFamily),
            E(android::register_android_graphics_text_MeasuredText),
            E(android::register_android_graphics_text_LineBreaker),
            E(android::register_android_graphics_text_TextShaper),
            E(android::register_android_graphics_text_GraphemeBreak),
            E(android::register_android_graphics_MeshSpecification),
            E(android::register_android_graphics_Mesh),
            E(android::register_android_util_PathParser),
            E(android::register_android_content_AssetManager),
            E(android::register_android_content_res_ApkAssets),
            E(android::register_android_content_StringBlock),
            E(android::register_android_content_XmlBlock),
            E(android::register_android_view_RenderNode),
            E(android::register_android_view_MotionEvent),
            E(android::register_android_view_KeyEvent),
            E(android::register_android_view_InputDevice),
            E(android::register_android_view_VelocityTracker),
            E(android::register_android_view_KeyCharacterMap),
            E(azk_register_android_view_HostedInputDispatcher),
            E(android::register_android_animation_PropertyValuesHolder),
            E(android::register_android_view_DisplayListCanvas),
            E(android::register_android_view_ThreadedRenderer),
            E(android::register_android_graphics_HardwareRendererObserver),
            E(android::register_android_graphics_HardwareBufferRenderer),
            E(android::register_android_graphics_animation_NativeInterpolatorFactory),
            E(android::register_android_graphics_animation_RenderNodeAnimator),
            E(android::register_com_android_internal_util_VirtualRefBasePtr),
            E(android::register_android_backup_BackupDataInput),
            E(android::register_android_backup_BackupDataOutput),
            E(android::register_com_android_internal_os_ApplicationSharedMemory),
            E(android::register_android_net_LocalSocketImpl),
            E(register_android_opengl_jni_GLES20),
            E(register_android_opengl_jni_GLES30),
            E(register_android_opengl_jni_GLES31),
            E(register_android_opengl_jni_GLES32),
            E(android::register_android_app_backup_FullBackup),
            E(android::register_android_database_CursorWindow),
            E(android::register_android_database_SQLiteConnection),
            E(android::register_android_database_SQLiteGlobal),
            E(android::register_android_database_SQLiteDebug),
            E(android::register_android_database_SQLiteRawStatement),
            E(android::register_android_graphics_drawable_VectorDrawable),
            E(android::register_android_graphics_drawable_AnimatedVectorDrawable),
            E(register_android_graphics_drawable_AnimatedImageDrawable),
            E(register_android_graphics_ImageDecoder),
    };
#undef E
    size_t limit = sizeof(entries) / sizeof(entries[0]);
    if (const char* n = getenv("AZK_REG_N")) limit = (size_t)atoi(n);
    const char* only = getenv("AZK_REG_ONLY");
    size_t idx = 0;
    for (const Entry& e : entries) {
        if (idx++ >= limit) break;
        if (only && !strstr(e.name, only)) continue;
        fprintf(stderr, "azk: registering %s\n", e.name);
        if (e.fn(env) < 0) {
            fprintf(stderr, "azk: JNI registration failed: %s\n", e.name);
            if (env->ExceptionCheck()) env->ExceptionDescribe();
            return -1;
        }
        if (env->ExceptionCheck()) {
            fprintf(stderr, "azk: pending exception after %s\n", e.name);
            env->ExceptionDescribe();
            return -1;
        }
    }
    return 0;
}

}  // extern "C"

// Pinned CameraMetadata_setupGlobalVendorTagDescriptor control flow over the
// host's shared Java/NDK Binder. Reusing ICameraService's C++ proxy would pull
// in the native capture/output stack. Resolve transaction IDs and errors from
// the generated Java AIDL; keep descriptor parsing in stock C++ parcelables.
namespace {
jint cameraServiceConstant(JNIEnv* env, const char* name) {
    ScopedLocalRef<jclass> cls(env, env->FindClass("android/hardware/ICameraService"));
    if (!cls.get()) return -1;
    jfieldID field = env->GetStaticFieldID(cls.get(), name, "I");
    return field ? env->GetStaticIntField(cls.get(), field) : -1;
}
android::sp<android::IBinder> cameraMetadataService(JNIEnv* env) {
    ScopedLocalRef<jclass> cls(env, env->FindClass("android/os/ServiceManager"));
    if (!cls.get()) return nullptr;
    jmethodID get = env->GetStaticMethodID(cls.get(), "getService",
            "(Ljava/lang/String;)Landroid/os/IBinder;");
    if (!get) return nullptr;
    ScopedLocalRef<jstring> name(env, env->NewStringUTF("media.camera"));
    if (!name.get()) return nullptr;
    const jvalue args[] = {{.l = name.get()}};
    ScopedLocalRef<jobject> service(env, env->CallStaticObjectMethodA(cls.get(), get, args));
    if (env->ExceptionCheck() || !service.get()) return nullptr;
    return android::IBinder::fromNative(AIBinder_fromJavaBinder(env, service.get()));
}
android::binder::Status cameraMetadataServiceCall(JNIEnv* env,
        const android::sp<android::IBinder>& service, const char* transactionName,
        android::Parcelable* result) {
    using namespace android;
    ScopedLocalRef<jclass> cls(env, env->FindClass("android/hardware/ICameraService$Stub"));
    if (!cls.get()) return binder::Status::fromStatusT(BAD_VALUE);
    jfieldID field = env->GetStaticFieldID(cls.get(), transactionName, "I");
    if (!field) return binder::Status::fromStatusT(BAD_VALUE);
    const jint code = env->GetStaticIntField(cls.get(), field);
    Parcel request, response;
    status_t status = request.writeInterfaceToken(String16("android.hardware.ICameraService"));
    if (status == OK) status = service->transact(code, request, &response);
    if (status != OK) return binder::Status::fromStatusT(status);
    binder::Status remote;
    status = remote.readFromParcel(response);
    if (status != OK) return binder::Status::fromStatusT(status);
    if (!remote.isOk()) return remote;
    status = response.readParcelable(result);
    return status == OK ? remote : binder::Status::fromStatusT(status);
}
} // namespace

extern "C" jint azk_camera_setup_vendor_tags(JNIEnv *env) {
    using namespace android;
    const jint disconnected = cameraServiceConstant(env, "ERROR_DISCONNECTED");
    const jint invalidOperation = cameraServiceConstant(env, "ERROR_INVALID_OPERATION");
    if (env->ExceptionCheck()) return -1;
    sp<IBinder> cameraService = cameraMetadataService(env);
    if (env->ExceptionCheck()) return -1;
    status_t err = cameraService ? OK : NAME_NOT_FOUND;

    if (err != OK) {
        ALOGE("%s: Failed to get camera service, received error %s (%d)", __FUNCTION__,
                strerror(-err), err);
        return disconnected;
    }

    sp<VendorTagDescriptor> desc = new VendorTagDescriptor();
    binder::Status res = cameraMetadataServiceCall(env, cameraService, "TRANSACTION_getCameraVendorTagDescriptor", desc.get());

    if (res.serviceSpecificErrorCode() == disconnected) {
        // No camera module available, not an error on devices with no cameras
        VendorTagDescriptor::clearGlobalVendorTagDescriptor();
        return OK;
    } else if (!res.isOk()) {
        VendorTagDescriptor::clearGlobalVendorTagDescriptor();
        ALOGE("%s: Failed to setup vendor tag descriptors: %s", __FUNCTION__,
              res.toString8().c_str());
        return res.serviceSpecificErrorCode();
    }
    if (0 < desc->getTagCount()) {
        err = VendorTagDescriptor::setAsGlobalVendorTagDescriptor(desc);
    } else {
        sp<VendorTagDescriptorCache> cache = new VendorTagDescriptorCache();
        binder::Status res = cameraMetadataServiceCall(env, cameraService, "TRANSACTION_getCameraVendorTagCache", cache.get());
        if (res.serviceSpecificErrorCode() == disconnected) {
            // No camera module available, not an error on devices with no cameras
            VendorTagDescriptorCache::clearGlobalVendorTagCache();
            return OK;
        } else if (!res.isOk()) {
            VendorTagDescriptorCache::clearGlobalVendorTagCache();
            ALOGE("%s: Failed to setup vendor tag cache: %s", __FUNCTION__,
                  res.toString8().c_str());
            return res.serviceSpecificErrorCode();
        }

        err = VendorTagDescriptorCache::setAsGlobalVendorTagCache(cache);
    }

    if (err != OK) {
        return invalidOperation;
    }
    return OK;
}

// Pinned android_util_Binder::javaObjectForIBinder conversion over the shared
// Java/NDK Binder transport. The original bridge assumes kernel libbinder.
namespace android {
sp<IBinder> ibinderForJavaObject(JNIEnv* env, jobject object) {
    if (!object) return nullptr;
    return IBinder::fromNative(AIBinder_fromJavaBinder(env, object));
}
jobject javaObjectForIBinder(JNIEnv* env, const sp<IBinder>& binder) {
    if (!binder) return nullptr;
    AIBinder* native = binder->acquireNative();
    jobject result = AIBinder_toJavaBinder(env, native);
    AIBinder_decStrong(native);
    return result;
}
} // namespace android
