// artzig: force-included into every island TU (build/island.zig). AOSP's
// bionic/libc++ transitively provide these; glibc/zig libc++ do not.
#pragma once
#include <limits>
#include <atomic>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <optional>
#include <bitset>
#include <memory>
#include <chrono>
