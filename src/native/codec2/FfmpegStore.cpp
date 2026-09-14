// The pinned C2 platform store supplies allocator configuration and reflection;
// this host store selects FFmpeg components in place of Android codec DSOs.
// The original AIDL ComponentStore owns Binder transport and client lifetimes.
#include "FfmpegStore.h"
#include "FfmpegComponent.h"
#include <C2PlatformSupport.h>
#include <atomic>

namespace azk::codec2 {
namespace {
class Store final : public C2ComponentStore {
public:
    explicit Store(C2Component::domain_t domain) : mDomain(domain),
              mPlatform(android::GetCodec2PlatformComponentStore()),
              mReflector(std::static_pointer_cast<C2ReflectorHelper>(mPlatform->getParamReflector())) {}
    C2String getName() const override { return "artzig"; }
    c2_status_t createComponent(C2String name, std::shared_ptr<C2Component> *const out) override {
        if (!out) return C2_BAD_VALUE;
        out->reset();
        if (!supports(name)) return C2_NOT_FOUND;
        return createFfmpegComponent(name, mNextId.fetch_add(1), mReflector, out);
    }
    c2_status_t createInterface(C2String name, std::shared_ptr<C2ComponentInterface> *const out) override {
        if (!out) return C2_BAD_VALUE;
        out->reset();
        if (!supports(name)) return C2_NOT_FOUND;
        return createFfmpegInterface(name, mNextId.fetch_add(1), mReflector, out);
    }
    std::vector<std::shared_ptr<const C2Component::Traits>> listComponents() override {
        std::vector<std::shared_ptr<const C2Component::Traits>> result;
        for (auto &traits : componentTraits()) if (traits->domain == mDomain) result.push_back(std::move(traits));
        return result;
    }
    c2_status_t copyBuffer(std::shared_ptr<C2GraphicBuffer> src, std::shared_ptr<C2GraphicBuffer> dst) override {
        return mPlatform->copyBuffer(std::move(src), std::move(dst));
    }
    c2_status_t query_sm(const std::vector<C2Param *> &stack,
            const std::vector<C2Param::Index> &indices,
            std::vector<std::unique_ptr<C2Param>> *const heap) const override {
        return mPlatform->query_sm(stack, indices, heap);
    }
    c2_status_t config_sm(const std::vector<C2Param *> &params,
            std::vector<std::unique_ptr<C2SettingResult>> *const failures) override {
        return mPlatform->config_sm(params, failures);
    }
    std::shared_ptr<C2ParamReflector> getParamReflector() const override { return mReflector; }
    c2_status_t querySupportedParams_nb(std::vector<std::shared_ptr<C2ParamDescriptor>> *const params) const override {
        return mPlatform->querySupportedParams_nb(params);
    }
    c2_status_t querySupportedValues_sm(std::vector<C2FieldSupportedValuesQuery> &fields) const override {
        return mPlatform->querySupportedValues_sm(fields);
    }
private:
    bool supports(const C2String &name) {
        for (const auto &traits : listComponents()) if (traits->name == name) return true;
        return false;
    }
    const C2Component::domain_t mDomain;
    std::shared_ptr<C2ComponentStore> mPlatform;
    std::shared_ptr<C2ReflectorHelper> mReflector;
    std::atomic<c2_node_id_t> mNextId{1};
};
}
std::shared_ptr<C2ComponentStore> createStore(C2Component::domain_t domain) {
    return std::make_shared<Store>(domain);
}
}
