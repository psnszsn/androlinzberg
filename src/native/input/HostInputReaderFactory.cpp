// inputflinger's reader factory (reader/InputReaderFactory.cpp) for a hosted
// session. Stock InputManager calls createInputReader once; the stock
// InputReader is constructed exactly as there, over HostEventHub instead of
// EventHub, because the session's devices come from the Wayland seat rather
// than /dev/input. The stock factory source is not compiled.
//
// The reader's policy is stock NativeInputManager, wrapped to observe one
// value: the pointer capture request it hands the reader. On a device the
// mouse keeps sending relative motion; the Wayland seat must ask the
// compositor to lock its pointer for that, below Java.
#include <InputReader.h>
#include <InputReaderFactory.h>

#include <functional>
#include <mutex>

#include "HostEventHub.h"

namespace android {

std::shared_ptr<HostEventHub> createHostEventHub();

namespace {

std::mutex gCaptureLock;
std::function<void(const PointerCaptureRequest&)> gCaptureObserver;

class HostReaderPolicy final : public InputReaderPolicyInterface {
public:
    explicit HostReaderPolicy(sp<InputReaderPolicyInterface> stock) : mStock(std::move(stock)) {}

    void getReaderConfiguration(InputReaderConfiguration* outConfig) override {
        mStock->getReaderConfiguration(outConfig);
        const PointerCaptureRequest& request = outConfig->pointerCaptureRequest;
        std::scoped_lock lock(gCaptureLock);
        if (request == mLastCapture) return;
        mLastCapture = request;
        if (gCaptureObserver) gCaptureObserver(request);
    }
    void notifyInputDevicesChanged(const std::vector<InputDeviceInfo>& inputDevices) override {
        mStock->notifyInputDevicesChanged(inputDevices);
    }
    void notifyTouchpadHardwareState(const SelfContainedHardwareState& schs, int32_t deviceId) override {
        mStock->notifyTouchpadHardwareState(schs, deviceId);
    }
    void notifyTouchpadGestureInfo(GestureType type, int32_t deviceId) override {
        mStock->notifyTouchpadGestureInfo(type, deviceId);
    }
    void notifyTouchpadThreeFingerTap() override { mStock->notifyTouchpadThreeFingerTap(); }
    std::shared_ptr<KeyCharacterMap> getKeyboardLayoutOverlay(
            const InputDeviceIdentifier& identifier,
            const std::optional<KeyboardLayoutInfo> keyboardLayoutInfo) override {
        return mStock->getKeyboardLayoutOverlay(identifier, keyboardLayoutInfo);
    }
    std::string getDeviceAlias(const InputDeviceIdentifier& identifier) override {
        return mStock->getDeviceAlias(identifier);
    }
    TouchAffineTransformation getTouchAffineTransformation(const std::string& inputDeviceDescriptor,
                                                           ui::Rotation surfaceRotation) override {
        return mStock->getTouchAffineTransformation(inputDeviceDescriptor, surfaceRotation);
    }
    void notifyStylusGestureStarted(int32_t deviceId, nsecs_t eventTime) override {
        mStock->notifyStylusGestureStarted(deviceId, eventTime);
    }
    bool isInputMethodConnectionActive() override { return mStock->isInputMethodConnectionActive(); }
    std::optional<DisplayViewport> getPointerViewportForAssociatedDisplay(
            ui::LogicalDisplayId associatedDisplayId) override {
        return mStock->getPointerViewportForAssociatedDisplay(associatedDisplayId);
    }

private:
    sp<InputReaderPolicyInterface> mStock;
    PointerCaptureRequest mLastCapture;
};

} // namespace

/** Observes the capture request the reader applies; called on the reader thread. */
void setHostPointerCaptureObserver(std::function<void(const PointerCaptureRequest&)> observer) {
    std::scoped_lock lock(gCaptureLock);
    gCaptureObserver = std::move(observer);
}

std::unique_ptr<InputReaderInterface> createInputReader(const sp<InputReaderPolicyInterface>& policy,
                                                        InputListenerInterface& listener) {
    return std::make_unique<InputReader>(createHostEventHub(), sp<HostReaderPolicy>::make(policy), listener);
}

} // namespace android
