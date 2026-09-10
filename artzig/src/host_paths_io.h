#pragma once
#include "host_paths.h"

// Follow libcore io_util.h's string acquisition/release and exception ordering.
// These forms are used only by UnixFileSystem's pathname operations.
#define WITH_HOSTED_PLATFORM_STRING(env, expression, var) \
    WITH_PLATFORM_STRING(env, expression, var##_original) \
    const char* var = azk_host_path(var##_original); \
    if (var == NULL) JNU_ThrowOutOfMemoryError(env, "platform path"); \
    if (var != NULL)
#define WITH_HOSTED_FIELD_PLATFORM_STRING(env, object, id, var) \
    WITH_HOSTED_PLATFORM_STRING(env, ((object == NULL) ? NULL : \
        (*(env))->GetObjectField((env), (object), (id))), var)
#define END_HOSTED_PLATFORM_STRING(env, var) \
    azk_host_path_release(var##_original, var); \
    END_PLATFORM_STRING(env, var##_original)

// UnixNativeDispatcher receives pathname bytes in Java-owned native buffers.
// Keep those buffers borrowed and release only the mapped string on every exit.
struct HostedNativePath {
    const char* original;
    const char* mapped;
};
static inline void releaseHostedNativePath(struct HostedNativePath* path) {
    azk_host_path_release(path->original, path->mapped);
}
#define HOSTED_NATIVE_PATH(env, var, expression, failure) \
    struct HostedNativePath var##_storage __attribute__((cleanup(releaseHostedNativePath))) = \
        { .original = (expression), .mapped = azk_host_path(expression) }; \
    const char* var = var##_storage.mapped; \
    if (var == NULL) { JNU_ThrowOutOfMemoryError(env, "platform path"); return failure; }
