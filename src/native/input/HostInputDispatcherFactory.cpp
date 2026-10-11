// inputflinger's dispatcher factory (dispatcher/InputDispatcherFactory.cpp)
// for a hosted session. Stock InputManager owns the stock InputDispatcher it
// returns, exactly as there. The session's native desktop integration (the
// SurfaceFlinger task map and native drag, src/native/HostInputDispatcher.cpp)
// reaches that same instance through hostInputDispatcher() instead of a
// second dispatcher. The stock factory source is not compiled.
//
// On a device the dispatcher's constructor subscribes to SurfaceFlinger's
// window infos (InputDispatcher.cpp, under __ANDROID__, which this host build
// does not define). The hosted dispatcher subscribes here instead, through the
// hosted entry that also carries each window's task for drag targeting.
#include <InputDispatcher.h>
#include <InputDispatcherFactory.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/WindowInfosListener.h>

#include <atomic>
#include <functional>
#include <mutex>

namespace android {

using inputdispatcher::InputDispatcher;
using HostWindowTaskResolver = std::function<InputDispatcher::HostedWindowTasks(const gui::WindowInfosUpdate&)>;

namespace {

std::atomic<InputDispatcher*> gDispatcher{nullptr};
std::mutex gResolverLock;
HostWindowTaskResolver gResolver;

class WindowListener final : public gui::WindowInfosListener {
public:
    explicit WindowListener(InputDispatcher& dispatcher) : mDispatcher(dispatcher) {}
    void onWindowInfosChanged(const gui::WindowInfosUpdate& update) override {
        InputDispatcher::HostedWindowTasks tasks;
        {
            std::scoped_lock lock(gResolverLock);
            if (gResolver) tasks = gResolver(update);
        }
        mDispatcher.onHostedWindowInfosChanged(update, std::move(tasks));
    }

private:
    InputDispatcher& mDispatcher;
};

/** The stock dispatcher, subscribed to SurfaceFlinger for its lifetime. */
class HostDispatcher final : public InputDispatcher {
public:
    explicit HostDispatcher(InputDispatcherPolicyInterface& policy)
          : InputDispatcher(policy), mListener(sp<WindowListener>::make(*this)) {
        gDispatcher.store(this);
        SurfaceComposerClient::getDefault()->addWindowInfosListener(mListener);
    }
    ~HostDispatcher() override {
        SurfaceComposerClient::getDefault()->removeWindowInfosListener(mListener);
        gDispatcher.store(nullptr);
    }

private:
    sp<WindowListener> mListener;
};

} // namespace

std::unique_ptr<InputDispatcherInterface> createInputDispatcher(InputDispatcherPolicyInterface& policy) {
    return std::make_unique<HostDispatcher>(policy);
}

/** The dispatcher stock InputManager created; null before InputManagerService starts. */
InputDispatcher* hostInputDispatcher() {
    return gDispatcher.load();
}

/** Supplies each input window's task with later window infos (drag targeting). */
void setHostWindowTaskResolver(HostWindowTaskResolver resolver) {
    std::scoped_lock lock(gResolverLock);
    gResolver = std::move(resolver);
}

} // namespace android
