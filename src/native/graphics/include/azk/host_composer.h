#pragma once
#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
typedef struct AzkHostComposer AzkHostComposer;
typedef void (*AzkHostVsync)(void *, int64_t);
AzkHostComposer *azk_host_composer_create(int64_t period, AzkHostVsync, void *);
void azk_host_composer_destroy(AzkHostComposer *);
void azk_host_composer_vsync(AzkHostComposer *, bool);
void azk_host_composer_power(AzkHostComposer *, bool);
int32_t azk_host_composer_layer_create(AzkHostComposer *, uint64_t *);
int32_t azk_host_composer_layer_destroy(AzkHostComposer *, uint64_t);
int32_t azk_host_composer_layer_check(AzkHostComposer *, uint64_t, bool);
int32_t azk_host_composer_layer_composition(AzkHostComposer *, uint64_t,
                                            int32_t);
int32_t azk_host_composer_validate(AzkHostComposer *, uint32_t *);
int32_t azk_host_composer_changes(AzkHostComposer *, uint64_t *, uint32_t,
                                  uint32_t *);
int32_t azk_host_composer_accept(AzkHostComposer *);
int32_t azk_host_composer_present(AzkHostComposer *, bool hasTarget);
#ifdef __cplusplus
}
#include <memory>
namespace android::Hwc2 {
class Composer;
std::unique_ptr<Composer> createHostComposer(int32_t width, int32_t height,
                                             int32_t period);
} // namespace android::Hwc2
#endif
