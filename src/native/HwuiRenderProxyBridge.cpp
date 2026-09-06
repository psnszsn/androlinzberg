// Narrow C ABI over the HWUI RenderProxy methods that Zig cannot call
// directly. Target state and JNI registration live in host_renderer.zig.
#include <renderthread/RenderProxy.h>
#include <RenderNode.h>
#include "ArtzigGpuPipeline.h"

using android::uirenderer::renderthread::RenderProxy;

extern "C" void azk_hwui_configure_host_surface(void* opaqueProxy, void* display,
                                                 void* surface, int width, int height) {
    auto* proxy = static_cast<RenderProxy*>(opaqueProxy);
    proxy->setHostSurface(display, surface, width, height);
    proxy->setContentDrawBounds(0, 0, width, height);
    proxy->forceDrawNextFrame();
}

extern "C" void azk_hwui_destroy_host_surface(void* opaqueProxy) {
    auto* proxy = static_cast<RenderProxy*>(opaqueProxy);
    proxy->setHostSurface(nullptr, nullptr, 0, 0);
    proxy->fence();
}

// RenderNode.adopt takes an already-retained native reference.
extern "C" void azk_hwui_retain_render_node(void* opaqueNode) {
    static_cast<android::uirenderer::RenderNode*>(opaqueNode)->incStrong(nullptr);
}

extern "C" void azk_hwui_release_render_node(void* opaqueNode) {
    static_cast<android::uirenderer::RenderNode*>(opaqueNode)->decStrong(nullptr);
}

extern "C" void azk_hwui_bind_egl_display(void* display) {
    android::uirenderer::skiapipeline::ArtzigGpuPipeline::bindHostDisplay(display);
}
