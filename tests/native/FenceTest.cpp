#include <binder/Parcel.h>
#include <fcntl.h>
#include <cerrno>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <ui/Fence.h>
#include <unistd.h>
#include <GLES2/gl2.h>
#include <sync/sync.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "renderthread/EglManager.h"

using namespace android;

namespace {
// Exercise the public libui Fence/Parcel contracts in the same native island
// as HWUI. A pipe supplies deterministic readiness to libsync's poll path;
// it does not stand in for a kernel sync_file's merge or timestamp ioctls.
void testFence(JNIEnv* env, jclass) {
#define CHECK_FENCE(condition) \
    do { \
        if (!(condition)) { \
            jniThrowException(env, "java/lang/AssertionError", #condition); \
            return; \
        } \
    } while (0)

    CHECK_FENCE(!Fence::NO_FENCE->isValid());
    CHECK_FENCE(Fence::NO_FENCE->get() == -1);
    CHECK_FENCE(Fence::NO_FENCE->dup() == -1);
    CHECK_FENCE(Fence::NO_FENCE->wait(0) == OK);
    CHECK_FENCE(Fence::NO_FENCE->getSignalTime() == Fence::SIGNAL_TIME_INVALID);
    CHECK_FENCE(Fence::merge("empty", Fence::NO_FENCE, Fence::NO_FENCE) == Fence::NO_FENCE);

    int pipeFds[2];
    CHECK_FENCE(pipe2(pipeFds, O_CLOEXEC) == 0);
    auto fence = sp<Fence>::make(pipeFds[0]);
    base::unique_fd writer(pipeFds[1]);
    const int original = fence->get();
    CHECK_FENCE(fence->isValid());
    CHECK_FENCE(fence->wait(0) == -ETIME);
    CHECK_FENCE(fence->wait(1) == -ETIME);
    CHECK_FENCE(fence->getStatus() == Fence::Status::Unsignaled);
    base::unique_fd duplicate(fence->dup());
    CHECK_FENCE(duplicate.get() >= 0 && duplicate.get() != original);
    auto shared = fence;
    fence.clear();
    CHECK_FENCE(fcntl(original, F_GETFD) >= 0);
    CHECK_FENCE(write(writer.get(), "x", 1) == 1);
    CHECK_FENCE(shared->waitForever("stock-fence-test") == OK);
    CHECK_FENCE(shared->getStatus() == Fence::Status::Signaled);

    Parcel parcel;
    CHECK_FENCE(parcel.write(*shared) == OK);
    shared.clear();
    CHECK_FENCE(fcntl(original, F_GETFD) == -1 && errno == EBADF);
    parcel.setDataPosition(0);
    auto restored = sp<Fence>::make();
    CHECK_FENCE(parcel.read(*restored) == OK);
    parcel.freeData();
    CHECK_FENCE(restored->isValid() && restored->wait(0) == OK);
    const int restoredFd = restored->get();
    restored.clear();
    CHECK_FENCE(fcntl(restoredFd, F_GETFD) == -1 && errno == EBADF);
    CHECK_FENCE(fcntl(duplicate.get(), F_GETFD) >= 0);

    auto empty = sp<Fence>::make();
    CHECK_FENCE(parcel.write(*empty) == OK);
    parcel.setDataPosition(0);
    auto emptyRestored = sp<Fence>::make();
    CHECK_FENCE(parcel.read(*emptyRestored) == OK && !emptyRestored->isValid());

    uint32_t word = 2;
    const void* buffer = &word;
    size_t size = sizeof(word);
    const int* descriptors = nullptr;
    size_t count = 0;
    CHECK_FENCE(empty->unflatten(buffer, size, descriptors, count) == BAD_VALUE);
    word = 1;
    buffer = &word;
    size = sizeof(word);
    CHECK_FENCE(empty->unflatten(buffer, size, descriptors, count) == NO_MEMORY);
    buffer = &word;
    size = sizeof(word) - 1;
    CHECK_FENCE(empty->unflatten(buffer, size, descriptors, count) == NO_MEMORY);
#undef CHECK_FENCE
}

// Called with the existing EGL smoke's context current. This exercises the
// original EglManager acquire/release contract with a real sync_file.
void testHostGpuFence(JNIEnv* env, jclass) {
#define CHECK_GPU_FENCE(condition) \
    do { \
        if (!(condition)) { \
            jniThrowException(env, "java/lang/AssertionError", #condition); \
            return; \
        } \
    } while (0)
    struct ScopedManager {
        EGLDisplay display = eglGetCurrentDisplay();
        EGLContext context = eglGetCurrentContext();
        EGLSurface draw = eglGetCurrentSurface(EGL_DRAW);
        EGLSurface read = eglGetCurrentSurface(EGL_READ);
        uirenderer::renderthread::EglManager manager;
        ~ScopedManager() {
            manager.destroy();
            eglMakeCurrent(display, draw, read, context);
        }
    } scope;
    auto& manager = scope.manager;
    manager.initialize();
    EGLSyncKHR eglFence = EGL_NO_SYNC_KHR;
    int fd = -1;
    CHECK_GPU_FENCE(manager.createReleaseFence(true, &eglFence, &fd) == OK);
    base::unique_fd owner(fd);
    CHECK_GPU_FENCE(manager.eglDisplay() == eglGetCurrentDisplay() && fd >= 0);
    CHECK_GPU_FENCE(eglFence == EGL_NO_SYNC_KHR);
    CHECK_GPU_FENCE(manager.fenceWait(fd) == OK);
    CHECK_GPU_FENCE(fcntl(fd, F_GETFD) >= 0); // The wait must only borrow this fd.
    base::unique_fd copy(dup(fd));
    CHECK_GPU_FENCE(copy.get() >= 0);
    glFinish();
    owner.reset();
    CHECK_GPU_FENCE(sync_wait(copy.get(), 1000) == 0);
    CHECK_GPU_FENCE(manager.fenceWait(-1) == -EBADF);
#undef CHECK_GPU_FENCE
}
} // namespace

int register_dev_artzig_StockFenceSmoke(JNIEnv* env) {
    jclass cls = env->FindClass("dev/artzig/StockFenceSmoke");
    if (!cls) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"nativeTest", "()V", reinterpret_cast<void*>(testFence)},
        {"nativeHostGpuTest", "()V", reinterpret_cast<void*>(testHostGpuFence)},
    };
    int result = env->RegisterNatives(cls, methods, sizeof(methods) / sizeof(methods[0]));
    env->DeleteLocalRef(cls);
    return result;
}
