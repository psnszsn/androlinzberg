#pragma once

#include <android/hardware_buffer.h>
#include <jni.h>

#include <cstdint>

#include <include/core/SkRefCnt.h>

class GrDirectContext;
class SkColorSpace;
class SkImage;
class SkSurface;
struct gbm_device;

namespace artzig {

struct DmabufPlane {
    int fd = -1;  // Borrowed; valid while the AHardwareBuffer is alive.
    uint32_t offset = 0;
    uint32_t stride = 0;
    uint64_t modifier = 0;
};

struct DmabufDescription {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t drmFormat = 0;
    uint32_t planeCount = 0;
    DmabufPlane planes[4];
};

// Private host extensions at the AHardwareBuffer seam. Callers never need to
// know whether the storage was allocated or imported by GBM.
bool describeDmabuf(const AHardwareBuffer* buffer, DmabufDescription* outDescription);
gbm_device* hardwareBufferGbmDevice();

// Imports the buffer into the EGL/OpenGL context that is current on this
// thread and wraps it as a Ganesh render target. The returned surface retains
// the AHardwareBuffer and the imported EGL/GL resources for its lifetime.
sk_sp<SkSurface> makeHardwareBufferSurface(GrDirectContext* context,
                                           AHardwareBuffer* buffer,
                                           sk_sp<SkColorSpace> colorSpace);
sk_sp<SkImage> makeHardwareBufferImage(GrDirectContext* context,
                                       AHardwareBuffer* buffer,
                                       sk_sp<SkColorSpace> colorSpace);

}  // namespace artzig

namespace android {
AHardwareBuffer* android_hardware_HardwareBuffer_getNativeHardwareBuffer(
        JNIEnv* env, jobject hardwareBufferObj);
jobject android_hardware_HardwareBuffer_createFromAHardwareBuffer(
        JNIEnv* env, AHardwareBuffer* hardwareBuffer);
}  // namespace android
