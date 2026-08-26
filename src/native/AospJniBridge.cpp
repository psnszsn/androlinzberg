// AOSP C++ ABI bridge: register the framework/HWUI JNI natives with ART,
// mirroring libs/hwui/apex/jni_runtime.cpp (layoutlib's entry) minus the classes
// we don't ship yet (Movie and PDF).
#include <GraphicsJNI.h>
#include <SkGraphics.h>
#include <jni.h>
#include <Properties.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_BitmapRegionDecoder(JNIEnv*);
extern int register_android_graphics_RuntimeXfermode(JNIEnv*);
extern int register_android_graphics_Camera(JNIEnv*);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv*);
extern int register_android_graphics_Graphics(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv*);
extern int register_android_graphics_MaskFilter(JNIEnv*);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv*);
extern int register_android_graphics_Shader(JNIEnv*);
extern int register_android_graphics_RenderEffect(JNIEnv*);
extern int register_android_graphics_Typeface(JNIEnv*);

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

int azk_register_graphics_jni(JNIEnv* env) {
    env->GetJavaVM(&g_azk_vm);
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
            E(azk_register_hardware_buffer),
            E(android::register_android_graphics_Canvas),
            E(android::register_android_graphics_Color),
            E(android::register_android_graphics_ColorSpace),
            E(register_android_graphics_Graphics),
            E(register_android_graphics_Bitmap),
            E(register_android_graphics_BitmapFactory),
            E(register_android_graphics_BitmapRegionDecoder),
            E(register_android_graphics_RuntimeXfermode),
            E(register_android_graphics_Camera),
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
            E(android::register_android_animation_PropertyValuesHolder),
            E(android::register_android_view_DisplayListCanvas),
            E(android::register_android_view_ThreadedRenderer),
            E(android::register_android_graphics_HardwareRendererObserver),
            E(android::register_android_graphics_HardwareBufferRenderer),
            E(android::register_android_graphics_animation_NativeInterpolatorFactory),
            E(android::register_android_graphics_animation_RenderNodeAnimator),
            E(android::register_com_android_internal_util_VirtualRefBasePtr),
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
