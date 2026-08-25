// artzig stub for minikin's Rust cxx hyphenator bridge.
// The Rust path is gated behind features::rust_hyphenator(), which is false
// off-Android (see minikin FeatureFlags.h), so HyphenatorRust is never
// instantiated — this header only has to let it compile.
#pragma once
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <string>

namespace rust {
namespace cxxbridge1 {
template <typename T>
struct Slice {
    Slice(const T* data, size_t size) : data_(data), size_(size) {}
    const T* data_;
    size_t size_;
};
}  // namespace cxxbridge1

template <typename T>
struct Box {
    T* ptr = nullptr;
    T& operator*() const { return *ptr; }
};

struct Hyphenator;

template <typename T>
inline Box<Hyphenator> load_hyphenator(cxxbridge1::Slice<T>, size_t, size_t, const std::string&) {
    abort();  // rust_hyphenator flag is always false here
}
template <typename A, typename B>
inline void hyphenate(const Hyphenator&, cxxbridge1::Slice<A>, cxxbridge1::Slice<B>) {
    abort();
}
}  // namespace rust
