// Exercise the original Codec2 allocator contract over hosted gralloc.
#include <C2AllocatorBlob.h>
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
    assert(azk_graphics_live_buffers() == baselineBuffers);
    assert(azk_graphics_live_bytes() == baselineBytes);
    std::cout << "PASS original C2AllocatorBlob capacity, map bounds, overlapping maps, import ownership and release accounting" << std::endl;
}
