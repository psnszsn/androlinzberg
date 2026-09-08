// Source: native/webview/plat_support/graphic_buffer_impl.cpp. The legacy
// WebView GL table uses the AOSP host GraphicBuffer, which stores RGBA pixels
// in memory and exposes lockAsync/unlockAsync rather than device gralloc APIs.
// Modern WebView draws through the original HWUI functor callbacks; this class
// preserves the older table's CPU buffer ownership and mapping contract.
#include "graphic_buffer_impl.h"
#include <limits>
#include <new>
#include <system/graphics.h>

namespace android {
GraphicBufferImpl::GraphicBufferImpl(uint32_t w, uint32_t h)
    : mBuffer(sp<GraphicBuffer>::make(w, h)) {
    auto* native = static_cast<ANativeWindowBuffer*>(mBuffer.get());
    native->stride = w;
    native->format = HAL_PIXEL_FORMAT_RGBA_8888;
}
GraphicBufferImpl::~GraphicBufferImpl() = default;
long GraphicBufferImpl::Create(int w, int h) {
    if (w <= 0 || h <= 0 || uint64_t(w) * uint64_t(h) >
            std::numeric_limits<uint32_t>::max() / sizeof(uint32_t)) return 0;
    try { return reinterpret_cast<intptr_t>(new GraphicBufferImpl(w, h)); }
    catch (const std::bad_alloc&) { return 0; }
}
void GraphicBufferImpl::Release(long id) { delete reinterpret_cast<GraphicBufferImpl*>(id); }
int GraphicBufferImpl::MapStatic(long id, AwMapMode mode, void** address) {
    return reinterpret_cast<GraphicBufferImpl*>(id)->Map(mode, address);
}
int GraphicBufferImpl::UnmapStatic(long id) { return reinterpret_cast<GraphicBufferImpl*>(id)->Unmap(); }
void* GraphicBufferImpl::GetNativeBufferStatic(long id) { return reinterpret_cast<GraphicBufferImpl*>(id)->GetNativeBuffer(); }
uint32_t GraphicBufferImpl::GetStrideStatic(long id) { return reinterpret_cast<GraphicBufferImpl*>(id)->GetStride(); }
status_t GraphicBufferImpl::Map(AwMapMode mode, void** address) {
    if (mode != MAP_READ_ONLY && mode != MAP_WRITE_ONLY && mode != MAP_READ_WRITE) return INVALID_OPERATION;
    if (!address) return BAD_VALUE;
    return mBuffer->lockAsync(0, mBuffer->getBounds(), address, -1);
}
status_t GraphicBufferImpl::Unmap() {
    int fence = -1;
    return mBuffer->unlockAsync(&fence); // Host CPU buffers have no release fence.
}
status_t GraphicBufferImpl::InitCheck() const { return mBuffer ? NO_ERROR : NO_MEMORY; }
void* GraphicBufferImpl::GetNativeBuffer() const { return static_cast<ANativeWindowBuffer*>(mBuffer.get()); }
uint32_t GraphicBufferImpl::GetStride() const { return mBuffer->getStride() * sizeof(uint32_t); }
} // namespace android
