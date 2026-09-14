#pragma once
#include <C2Component.h>
#include <util/C2InterfaceHelper.h>
#include <memory>

namespace azk::codec2 {
// FFmpeg is the host decoder implementation behind the original Codec2 HAL.
c2_status_t createFfmpegComponent(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2Component> *component);
c2_status_t createFfmpegInterface(const C2String &name, c2_node_id_t id,
        const std::shared_ptr<C2ReflectorHelper> &reflector,
        std::shared_ptr<C2ComponentInterface> *interface);
std::vector<std::shared_ptr<const C2Component::Traits>> componentTraits();
}
