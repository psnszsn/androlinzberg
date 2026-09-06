#pragma once
#include <memory>
namespace android {
class GrallocMapper;
class GrallocAllocator;
namespace azk {
std::unique_ptr<const GrallocMapper> createGrallocMapper();
std::unique_ptr<const GrallocAllocator> createGrallocAllocator();
} // namespace azk
} // namespace android
