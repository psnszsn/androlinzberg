// Pinned android_os_Debug.cpp reads Bionic allocator totals and VMAP_STACK.
// Host libc exposes different totals, and many Linux distributions expose BTF
// without /proc/config.gz. Keep these differences below the original JNI.
#include "RuntimeDiagnostics.h"
#include <vintf/KernelConfigs.h>
#include <string>
extern "C" int azk_kernel_vmap_stack();
extern "C" struct mallinfo2 azk_android_mallinfo() {
    struct mallinfo2 value = mallinfo2();
    value.usmblks = value.arena + value.hblkhd;
    value.uordblks += value.hblkhd;
    return value;
}
namespace android {
std::string GetUnreachableMemoryString(bool, size_t) {
    return "Unreachable-memory scanning is unavailable with the host allocator.\n";
}
namespace kernelconfigs {
status_t LoadDebugStackConfigs(std::map<std::string, std::string>* configs) {
    const status_t status = LoadKernelConfigs(configs);
    if (status == OK) return status;
    const int mapped = azk_kernel_vmap_stack();
    if (mapped < 0) return status;
    // This fallback is private to Debug.isVmapStack. It does not claim to
    // enumerate all kernel configuration or change VINTF's original queries.
    configs->clear();
    configs->emplace("CONFIG_VMAP_STACK", mapped ? "y" : "n");
    return OK;
}
}
} // namespace android
