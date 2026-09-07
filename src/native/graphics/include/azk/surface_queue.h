#pragma once
#include <azk/graphics_buffer.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef struct AzkSurfaceQueue AzkSurfaceQueue;
typedef struct AzkSurfaceFrame AzkSurfaceFrame;
typedef struct AIBinder AIBinder;
typedef struct AParcel AParcel;
// A notification only: callback must enqueue work, never call into the queue.
AzkSurfaceQueue *azk_surface_queue_create(uint32_t width, uint32_t height,
                                          void (*wake)(void *), void *context);
// Returns one owned native Binder reference for the stock producer interface.
AIBinder *azk_surface_queue_producer(AzkSurfaceQueue *);
// Writes the pinned android.view.Surface payload (without nullable presence).
// The caller owns the parcel; stock libgui exports the producer reference.
int32_t azk_surface_queue_write_surface(AzkSurfaceQueue *, AParcel *);
// Stops notifications and permanently abandons the producer before destroying
// the owner. Outstanding frame objects retain their buffers independently.
void azk_surface_queue_destroy(AzkSurfaceQueue *);
// NO_BUFFER_AVAILABLE is 2. On success the caller owns a frame lease; info.fd
// is borrowed until that lease is released. Never waits for GPU completion.
int32_t azk_surface_queue_acquire(AzkSurfaceQueue *, AzkSurfaceFrame **,
                                  AzkGraphicsBufferInfo *,
                                  uint64_t *frame_number);
// Worker-only bounded wait on the original producer acquire fence.
int32_t azk_surface_frame_wait(AzkSurfaceFrame *, int32_t timeout_ms);
// release consumes a compositor release fence FD (-1 after wl_buffer.release
// with implicit synchronization). The lease is consumed even on failure.
int32_t azk_surface_frame_release(AzkSurfaceFrame *, int32_t release_fence);
// A frame never submitted to a compositor retains its producer acquire fence
// when returned. Abandon permanently invalidates its queue on presentation
// loss.
void azk_surface_frame_discard(AzkSurfaceFrame *);
void azk_surface_frame_abandon(AzkSurfaceFrame *);
#ifdef __cplusplus
}
#endif
