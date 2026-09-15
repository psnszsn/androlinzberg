// Exercise the original Codec2 allocator contract over hosted gralloc.
#include <C2AllocatorBlob.h>
#include <C2AllocatorGralloc.h>
#include <android/binder_process.h>
#include <azk/graphics_buffer.h>
#include <C2PlatformSupport.h>
#include <cassert>
#include <cutils/native_handle.h>
#include <cstring>
#include <memory>
#include <iostream>
int main() {
    ABinderProcess_startThreadPool();
    const auto baselineBuffers = azk_graphics_live_buffers();
    const auto baselineBytes = azk_graphics_live_bytes();
    android::C2AllocatorBlob allocator(android::C2PlatformAllocatorStore::BLOB);
    std::shared_ptr<C2LinearAllocation> storage;
    C2MemoryUsage usage(C2MemoryUsage::CPU_READ, C2MemoryUsage::CPU_WRITE);
    constexpr size_t size = 1048579;
    assert(allocator.newLinearAllocation(size, usage, nullptr) == C2_BAD_VALUE);
    assert(allocator.newLinearAllocation(size, usage, &storage) == C2_OK);
    assert(storage->capacity() == size);
    void *address = nullptr;
    assert(storage->map(size, 1, usage, nullptr, &address) == C2_BAD_VALUE);
    assert(address == nullptr);
    assert(storage->map(0, size, usage, nullptr, &address) == C2_OK);
    std::memset(address, 0xa5, size);
    void *overlap = nullptr;
    assert(storage->map(7, 31, usage, nullptr, &overlap) == C2_OK);
    assert(overlap == static_cast<unsigned char *>(address) + 7);
    assert(storage->unmap(overlap, 30, nullptr) == C2_BAD_VALUE);
    assert(storage->unmap(overlap, 31, nullptr) == C2_OK);
    assert(storage->unmap(address, size, nullptr) == C2_OK);
    std::shared_ptr<C2LinearAllocation> imported;
    auto *handle = native_handle_clone(storage->handle());
    assert(handle && allocator.checkHandle(handle));
    assert(allocator.priorLinearAllocation(handle, &imported) == C2_OK);
    storage.reset();
    assert(imported->capacity() == size);
    assert(imported->map(0, size, usage, nullptr, &address) == C2_OK);
    for (size_t i=0; i<size; ++i) assert(static_cast<unsigned char *>(address)[i] == 0xa5);
    assert(imported->unmap(address, size, nullptr) == C2_OK);
    imported.reset();
    // Non-aligned dimensions exercise Android's independent chroma stride.
    android::C2AllocatorGralloc graphic(android::C2PlatformAllocatorStore::GRALLOC);
    std::shared_ptr<C2GraphicAllocation> yuv;
    assert(graphic.newGraphicAllocation(34, 18, HAL_PIXEL_FORMAT_YV12, usage, &yuv) == C2_OK);
    C2PlanarLayout layout{};
    uint8_t *planes[C2PlanarLayout::MAX_NUM_PLANES]{};
    const C2Rect rect(34, 18);
    assert(yuv->map(rect, usage, nullptr, &layout, planes) == C2_OK);
    assert(layout.type == C2PlanarLayout::TYPE_YUV && layout.numPlanes == 3);
    assert(layout.planes[0].rowInc == 48 && layout.planes[1].rowInc == 32 &&
           layout.planes[2].rowInc == 32);
    assert(planes[2] == planes[0] + 48 * 18 && planes[1] == planes[2] + 32 * 9);
    for (int plane = 0; plane < 3; ++plane) {
        const int w = plane ? 17 : 34, h = plane ? 9 : 18;
        for (int row = 0; row < h; ++row)
            std::memset(planes[plane] + row * layout.planes[plane].rowInc, 40 + plane, w);
    }
    assert(yuv->unmap(planes, rect, nullptr) == C2_OK);
    auto *yuvHandle = native_handle_clone(yuv->handle());
    std::shared_ptr<C2GraphicAllocation> yuvImported;
    assert(graphic.priorGraphicAllocation(yuvHandle, &yuvImported) == C2_OK);
    yuv.reset();
    assert(yuvImported->map(rect, usage, nullptr, &layout, planes) == C2_OK);
    for (int plane = 0; plane < 3; ++plane) {
        const int w = plane ? 17 : 34, h = plane ? 9 : 18;
        for (int row = 0; row < h; ++row)
            for (int col = 0; col < w; ++col)
                assert(planes[plane][row * layout.planes[plane].rowInc + col] == 40 + plane);
    }
    assert(yuvImported->unmap(planes, rect, nullptr) == C2_OK);
    yuvImported.reset();
    assert(azk_graphics_live_buffers() == baselineBuffers);
    assert(azk_graphics_live_bytes() == baselineBytes);
    std::cout << "PASS original C2 allocators: BLOB capacity/map bounds and YV12 planes, import ownership and release accounting" << std::endl;
}
