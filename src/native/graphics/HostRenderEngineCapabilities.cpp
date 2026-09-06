// Pinned SkiaVkRenderEngine.cpp defines these factory queries alongside its
// Vulkan implementation. This build uses SkiaGLRenderEngine; no Vulkan device
// or protected Vulkan context can be created at this host seam.
#include <renderengine/RenderEngine.h>

namespace android::renderengine {
bool RenderEngine::canSupport(GraphicsApi api) {
  return api == GraphicsApi::GL;
}
void RenderEngine::teardown(GraphicsApi) {
  // GL resources belong to each RenderEngine instance. No global Vulkan
  // interface was initialized by canSupport().
}
} // namespace android::renderengine
