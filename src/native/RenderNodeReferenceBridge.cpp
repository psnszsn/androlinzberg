// Narrow native RenderNode reference ownership used by hardware_snapshot.zig.
#include <RenderNode.h>

// RenderNode.adopt takes an already-retained native reference.
extern "C" void azk_hwui_retain_render_node(void* opaqueNode) {
    static_cast<android::uirenderer::RenderNode*>(opaqueNode)->incStrong(nullptr);
}

extern "C" void azk_hwui_release_render_node(void* opaqueNode) {
    static_cast<android::uirenderer::RenderNode*>(opaqueNode)->decStrong(nullptr);
}
