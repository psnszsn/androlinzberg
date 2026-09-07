// Host seam for pinned BufferItemConsumer/ConsumerBase. AOSP owns queue slots,
// generations, buffer caching and release-fence merging; Zig owns presentation.
#include <azk/surface_queue.h>
#include <gui/BufferItem.h>
#include <gui/BufferItemConsumer.h>
#include <gui/BufferQueue.h>
#include <gui/view/Surface.h>
#include <binder/Parcel.h>
#include <ui/GraphicBuffer.h>
#include <memory>
#include <mutex>

using namespace android;
static_assert(BufferItemConsumer::NO_BUFFER_AVAILABLE == 2);
namespace {
class WakeListener final : public ConsumerBase::FrameAvailableListener {
  std::mutex mutex;
  void (*wake)(void *);
  void *context;

public:
  WakeListener(void (*callback)(void *), void *ctx)
      : wake(callback), context(ctx) {}
  void onFrameAvailable(const BufferItem &) override {
    std::lock_guard lock(mutex);
    if (wake)
      wake(context);
  }
  void onFrameReplaced(const BufferItem &item) override {
    onFrameAvailable(item);
  }
  void stop() {
    std::lock_guard lock(mutex);
    wake = nullptr;
  }
};
} // namespace
struct AzkSurfaceQueue {
  sp<IGraphicBufferProducer> producer;
  sp<BufferItemConsumer> consumer;
  sp<WakeListener> listener;
  ~AzkSurfaceQueue() {
    if (listener)
      listener->stop();
    if (consumer)
      consumer->abandon();
  }
};
struct AzkSurfaceFrame {
  sp<BufferItemConsumer> consumer;
  BufferItem item;
};
extern "C" AzkSurfaceQueue *azk_surface_queue_create(uint32_t width,
                                                     uint32_t height,
                                                     void (*wake)(void *),
                                                     void *context) {
  if (width == 0 || height == 0 || width > 16384 || height > 16384)
    return nullptr;
  auto queue = std::make_unique<AzkSurfaceQueue>();
  sp<IGraphicBufferConsumer> consumer;
  BufferQueue::createBufferQueue(&queue->producer, &consumer);
  if (consumer->setDefaultBufferSize(width, height) != OK)
    return nullptr;
  queue->consumer =
      BufferItemConsumer::create(consumer, GRALLOC_USAGE_HW_TEXTURE, 2, false);
  queue->listener = sp<WakeListener>::make(wake, context);
  queue->consumer->setFrameAvailableListener(queue->listener);
  return queue.release();
}
extern "C" AIBinder *azk_surface_queue_producer(AzkSurfaceQueue *queue) {
  return queue ? IInterface::asBinder(queue->producer)->acquireNative()
               : nullptr;
}
extern "C" void azk_surface_queue_destroy(AzkSurfaceQueue *queue) {
  delete queue;
}
extern "C" int32_t azk_surface_queue_write_surface(AzkSurfaceQueue *queue,
                                                   AParcel *parcel) {
  if (!queue || !parcel) return BAD_VALUE;
  view::Surface surface;
  surface.name = String16("Wayland task output");
  surface.graphicBufferProducer = queue->producer;
  return surface.writeToParcel(Parcel::forNative(parcel));
}
extern "C" int32_t azk_surface_queue_acquire(AzkSurfaceQueue *queue,
                                             AzkSurfaceFrame **out,
                                             AzkGraphicsBufferInfo *info,
                                             uint64_t *number) {
  if (!queue || !out || !info || !number)
    return BAD_VALUE;
  *out = nullptr;
  auto frame = std::make_unique<AzkSurfaceFrame>();
  frame->consumer = queue->consumer;
  auto status = queue->consumer->acquireBuffer(&frame->item, 0, false);
  if (status != OK)
    return status;
  status = azk_graphics_describe(frame->item.mGraphicBuffer->handle, info);
  if (status != OK) {
    queue->consumer->releaseBuffer(frame->item, frame->item.mFence);
    return status;
  }
  // This seam currently consumes fully composed task outputs. Preserve the
  // queued dataspace, and fail explicitly until layer crop/transform and HDR
  // handling are wired through the composition/color-management milestone.
  info->dataspace = frame->item.mDataSpace;
  const Rect full(info->width, info->height);
  if (frame->item.mTransform != 0 || frame->item.mTransformToDisplayInverse ||
      (!frame->item.mCrop.isEmpty() && frame->item.mCrop != full) ||
      frame->item.mHdrMetadata.validTypes != 0) {
    ALOGE("host task output requires composition for crop, transform or HDR metadata");
    queue->consumer->releaseBuffer(frame->item, frame->item.mFence);
    return INVALID_OPERATION;
  }
  *number = frame->item.mFrameNumber;
  *out = frame.release();
  return OK;
}
extern "C" int32_t azk_surface_frame_wait(AzkSurfaceFrame *frame,
                                          int32_t timeout) {
  if (!frame || timeout < 0)
    return BAD_VALUE;
  return frame->item.mFence->wait(timeout);
}
extern "C" int32_t azk_surface_frame_release(AzkSurfaceFrame *raw, int32_t fd) {
  auto fence = sp<Fence>::make(fd);
  std::unique_ptr<AzkSurfaceFrame> frame(raw);
  if (!frame)
    return BAD_VALUE;
  return frame->consumer->releaseBuffer(frame->item, fence);
}
extern "C" void azk_surface_frame_discard(AzkSurfaceFrame *raw) {
  std::unique_ptr<AzkSurfaceFrame> frame(raw);
  if (frame)
    frame->consumer->releaseBuffer(frame->item, frame->item.mFence);
}
extern "C" void azk_surface_frame_abandon(AzkSurfaceFrame *raw) {
  std::unique_ptr<AzkSurfaceFrame> frame(raw);
  if (frame)
    frame->consumer->abandon();
}
