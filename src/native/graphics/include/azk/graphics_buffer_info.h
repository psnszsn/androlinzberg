#pragma once
#include <stdint.h>

typedef struct AzkGraphicsBufferInfo {
  uint32_t width, height, format, layers;
  uint64_t usage;
  uint32_t drm_format, stride, offset, reserved;
  uint64_t modifier, id;
  int32_t fd, dataspace;
} AzkGraphicsBufferInfo;
