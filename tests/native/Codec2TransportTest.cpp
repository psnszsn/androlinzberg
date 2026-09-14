// Exercise the pinned Codec2 work protocol and bufferpool across two authenticated
// processes. This input sink is a transport fixture, not a decoder component.
#include <azk/binder.h>
#include <cassert>
#include <aidl/android/hardware/media/c2/BnInputSink.h>
#include <aidl/android/hardware/media/bufferpool2/IClientManager.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/hardware_buffer.h>
#include <private/android/AHardwareBufferHelpers.h>
#include <bufferpool2/ClientManager.h>
#include <cutils/ashmem.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstring>
#include <iostream>
#include <thread>
#include "allocator.h"

using namespace std::chrono_literals;
namespace c2 = aidl::android::hardware::media::c2;
namespace bp = aidl::android::hardware::media::bufferpool2;
using bp::implementation::ClientManager;
using bp::implementation::ConnectionId;
using bp::implementation::TransactionId;
constexpr const char* sinkName = "dev.artzig.codec2.input";
constexpr const char* poolName = "dev.artzig.codec2.pool";

static void closeHandle(native_handle_t* handle) {
    if (handle) {
        native_handle_close(handle);
        native_handle_delete(handle);
    }
}

struct Sink final : c2::BnInputSink {
    std::shared_ptr<ClientManager> pool = ClientManager::getInstance();
    ndk::ScopedAStatus queue(const c2::WorkBundle& bundle) override {
        assert(AIBinder_getCallingPid() != getpid());
        if (bundle.works.size() != 1 || bundle.baseBlocks.size() != 1)
            return ndk::ScopedAStatus::fromServiceSpecificError(17);
        const auto& work = bundle.works[0];
        assert(work.input.ordinal.timestampUs == 1234567);
        assert(work.input.ordinal.frameIndex == 42);
        assert(work.input.flags == c2::FrameData::END_OF_STREAM);
        assert(work.input.buffers.size() == 1);
        assert(work.input.buffers[0].blocks.size() == 1);
        assert(work.input.buffers[0].blocks[0].index == 0);
        const auto& base = bundle.baseBlocks[0];
        if (base.getTag() == c2::BaseBlock::Tag::pooledBlock) {
            const auto& message = base.get<c2::BaseBlock::Tag::pooledBlock>();
            native_handle_t* handle = nullptr;
            std::shared_ptr<bp::BufferPoolData> buffer;
            assert(pool->receive(message.connectionId, message.transactionId,
                    message.bufferId, message.timestampUs, &handle, &buffer) == bp::ResultStatus::OK);
            assert(TestBufferPoolAllocator::Verify(handle, 0x77));
            assert(TestBufferPoolAllocator::Fill(handle, 0x55));
            // Keep pool ownership until verification and writing finish.
            closeHandle(handle);
            buffer.reset();
            pool->close(message.connectionId);
        } else if (base.getTag() == c2::BaseBlock::Tag::hwbBlock) {
            auto* buffer = base.get<c2::BaseBlock::Tag::hwbBlock>().get();
            assert(buffer && work.chainInfo.size() == sizeof(uint64_t));
            uint64_t inode;
            memcpy(&inode, work.chainInfo.data(), sizeof(inode));
            const auto* handle = AHardwareBuffer_getNativeHandle(buffer);
            assert(handle && handle->numFds >= 1);
            struct stat st;
            assert(fstat(handle->data[0], &st) == 0 && st.st_ino == inode);
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(buffer, &desc);
            assert(desc.width == 48 && desc.height == 32);
            void* pixels = nullptr;
            assert(AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                    AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1, nullptr, &pixels) == 0);
            for (uint32_t y = 0; y < desc.height; ++y)
                for (uint32_t x = 0; x < desc.width; ++x) {
                    auto& pixel = static_cast<uint32_t*>(pixels)[y * desc.stride + x];
                    assert(pixel == 0xff123456);
                    pixel = 0xff654321;
                }
            assert(AHardwareBuffer_unlock(buffer, nullptr) == 0);
        } else {
            assert(base.getTag() == c2::BaseBlock::Tag::nativeBlock);
            const auto& handle = base.get<c2::BaseBlock::Tag::nativeBlock>();
            assert(handle.fds.size() == 1 && handle.ints.size() == 2);
            struct stat st;
            assert(fstat(handle.fds[0].get(), &st) == 0);
            const uint64_t inode = uint32_t(handle.ints[0]) |
                    (uint64_t(uint32_t(handle.ints[1])) << 32);
            assert(st.st_ino == inode);
            void* address = mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_SHARED,
                    handle.fds[0].get(), 0);
            assert(address != MAP_FAILED);
            assert(static_cast<uint8_t*>(address)[0] == 0x77);
            static_cast<uint8_t*>(address)[0] = 0x55;
            assert(munmap(address, 4096) == 0);
        }
        return ndk::ScopedAStatus::ok();
    }
};

static c2::WorkBundle workBundle() {
    c2::WorkBundle bundle;
    bundle.works.resize(1);
    auto& input = bundle.works[0].input;
    input.ordinal.timestampUs = 1234567;
    input.ordinal.frameIndex = 42;
    input.flags = c2::FrameData::END_OF_STREAM;
    input.buffers.resize(1);
    input.buffers[0].blocks.resize(1);
    input.buffers[0].blocks[0].index = 0;
    return bundle;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    ABinderProcess_setThreadPoolMaxThreadCount(4);
    ABinderProcess_startThreadPool();
    if (!strcmp(argv[1], "server")) {
        auto sink = ndk::SharedRefBase::make<Sink>();
        assert(AServiceManager_addService(sink->asBinder().get(), sinkName) == STATUS_OK);
        assert(AServiceManager_addService(sink->pool->asBinder().get(), poolName) == STATUS_OK);
        std::cout << "ready" << std::endl;
        char c;
        while (std::cin.get(c)) {}
        // Bufferpool owns detached process-lifetime workers. Use the same exit
        // boundary as hosted AndroidRuntime, after the tests release buffers.
        std::_Exit(0);
    }
    assert(!strcmp(argv[1], "client"));
    auto binder = ndk::SpAIBinder(AServiceManager_checkService(sinkName));
    assert(binder.get() && AIBinder_isRemote(binder.get()));
    auto sink = c2::IInputSink::fromBinder(binder);
    assert(sink);
    int32_t version = 0;
    std::string hash;
    assert(sink->getInterfaceVersion(&version).isOk() && version == 1);
    assert(sink->getInterfaceHash(&hash).isOk() && hash == c2::IInputSink::hash);
    auto invalid = sink->queue(c2::WorkBundle{});
    assert(invalid.getServiceSpecificError() == 17);

    {
        ndk::ScopedFileDescriptor fd(ashmem_create_region("codec2-work", 4096));
        assert(fd.get() >= 0);
        void* address = mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd.get(), 0);
        assert(address != MAP_FAILED);
        static_cast<uint8_t*>(address)[0] = 0x77;
        struct stat st;
        assert(fstat(fd.get(), &st) == 0);
        aidl::android::hardware::common::NativeHandle handle;
        handle.ints = {int32_t(st.st_ino), int32_t(uint64_t(st.st_ino) >> 32)};
        handle.fds.emplace_back(dup(fd.get()));
        auto bundle = workBundle();
        bundle.baseBlocks.push_back(c2::BaseBlock::make<c2::BaseBlock::Tag::nativeBlock>(std::move(handle)));
        fd.set(-1); // The parcel owns the only producer FD; the mapping remains valid.
        assert(sink->queue(bundle).isOk());
        bundle.baseBlocks.clear();
        assert(static_cast<uint8_t*>(address)[0] == 0x55);
        assert(munmap(address, 4096) == 0);
    }

    {
        AHardwareBuffer_Desc desc{};
        desc.width = 48;
        desc.height = 32;
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN |
                AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        AHardwareBuffer* buffer = nullptr;
        assert(AHardwareBuffer_allocate(&desc, &buffer) == 0 && buffer);
        AHardwareBuffer_describe(buffer, &desc);
        void* pixels = nullptr;
        assert(AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
                -1, nullptr, &pixels) == 0);
        for (uint32_t y = 0; y < desc.height; ++y)
            for (uint32_t x = 0; x < desc.width; ++x)
                static_cast<uint32_t*>(pixels)[y * desc.stride + x] = 0xff123456;
        assert(AHardwareBuffer_unlock(buffer, nullptr) == 0);
        const auto* handle = AHardwareBuffer_getNativeHandle(buffer);
        struct stat st;
        assert(handle && handle->numFds >= 1 && fstat(handle->data[0], &st) == 0);
        auto bundle = workBundle();
        bundle.works[0].chainInfo.resize(sizeof(uint64_t));
        const uint64_t inode = st.st_ino;
        memcpy(bundle.works[0].chainInfo.data(), &inode, sizeof(inode));
        aidl::android::hardware::HardwareBuffer parcelable;
        AHardwareBuffer_acquire(buffer);
        parcelable.reset(buffer);
        bundle.baseBlocks.push_back(c2::BaseBlock::make<c2::BaseBlock::Tag::hwbBlock>(std::move(parcelable)));
        assert(sink->queue(bundle).isOk());
        bundle.baseBlocks.clear();
        assert(AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
                -1, nullptr, &pixels) == 0);
        for (uint32_t y = 0; y < desc.height; ++y)
            for (uint32_t x = 0; x < desc.width; ++x)
                assert(static_cast<uint32_t*>(pixels)[y * desc.stride + x] == 0xff654321);
        assert(AHardwareBuffer_unlock(buffer, nullptr) == 0);
        AHardwareBuffer_release(buffer);
    }

    {
        auto pool = ClientManager::getInstance();
        auto remote = bp::IClientManager::fromBinder(ndk::SpAIBinder(AServiceManager_checkService(poolName)));
        assert(remote && AIBinder_isRemote(remote->asBinder().get()));
        assert(remote->getInterfaceVersion(&version).isOk() && version == 2);
        ConnectionId connection, receiver;
        assert(pool->create(std::make_shared<TestBufferPoolAllocator>(), &connection) == bp::ResultStatus::OK);
        bool isNew = false;
        assert(pool->registerSender(remote, connection, &receiver, &isNew) == bp::ResultStatus::OK && isNew);
        std::vector<uint8_t> params;
        getTestAllocatorParams(&params);
        native_handle_t* handle = nullptr;
        std::shared_ptr<bp::BufferPoolData> buffer;
        assert(pool->allocate(connection, params, &handle, &buffer) == bp::ResultStatus::OK);
        assert(TestBufferPoolAllocator::Fill(handle, 0x77));
        TransactionId transaction;
        int64_t timestamp;
        assert(pool->postSend(receiver, buffer, &transaction, &timestamp) == bp::ResultStatus::OK);
        bp::BufferStatusMessage message;
        message.connectionId = receiver;
        message.transactionId = transaction;
        message.bufferId = buffer->mId;
        message.timestampUs = timestamp;
        auto bundle = workBundle();
        bundle.baseBlocks.push_back(c2::BaseBlock::make<c2::BaseBlock::Tag::pooledBlock>(message));
        buffer.reset(); // Transport must retain the allocation until the receiver releases it.
        assert(sink->queue(bundle).isOk());
        assert(TestBufferPoolAllocator::Verify(handle, 0x55));
        closeHandle(handle);
        pool->close(connection);
    }
    std::atomic<bool> died = false;
    auto* death = AIBinder_DeathRecipient_new([](void* context) {
        static_cast<std::atomic<bool>*>(context)->store(true);
    });
    assert(AIBinder_linkToDeath(binder.get(), death, &died) == STATUS_OK);
    std::cout << "PASS Codec2 metadata, versions, native FD, HardwareBuffer DMA-BUF identity and pooled ownership" << std::endl;
    std::cout << "READY_DEATH" << std::endl;
    for (int i = 0; i < 500 && !died; ++i) std::this_thread::sleep_for(10ms);
    assert(died);
    assert(sink->queue(c2::WorkBundle{}).getStatus() == STATUS_DEAD_OBJECT);
    AIBinder_DeathRecipient_delete(death);
    std::cout << "PASS Codec2 service death" << std::endl;
    std::_Exit(0);
}
