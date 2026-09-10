#pragma once
#include "host_paths.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedUtfChars.h>

// Preserve ScopedUtfChars' original null/UTF allocation exceptions and lifetime.
// Only native Linux pathname parameters use this type, never arbitrary strings.
class ScopedHostedPath {
public:
    ScopedHostedPath(JNIEnv* env, jstring path) : original_(env, path) {
        if (original_.c_str() != nullptr) {
            mapped_ = azk_host_path(original_.c_str());
            if (mapped_ == nullptr) jniThrowException(env, "java/lang/OutOfMemoryError", "platform path");
        }
    }
    ~ScopedHostedPath() {
        if (original_.c_str() != nullptr) azk_host_path_release(original_.c_str(), mapped_);
    }
    const char* c_str() const { return mapped_; }
    size_t size() const { return mapped_ == nullptr ? 0 : strlen(mapped_); }
    ScopedHostedPath(const ScopedHostedPath&) = delete;
    ScopedHostedPath& operator=(const ScopedHostedPath&) = delete;
private:
    ScopedUtfChars original_;
    const char* mapped_ = nullptr;
};
