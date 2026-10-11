// The device source of the stock InputReader for a hosted session.
//
// On a device, EventHub opens /dev/input/event* and learns each device's
// capabilities through evdev ioctls. An unprivileged session has no such
// devices: its input arrives from the compositor's Wayland seat. HostEventHub
// implements the same EventHubInterface over virtual evdev devices that the
// seat describes and feeds with raw input_event values, so the stock reader,
// its mappers, PointerChoreographer and the rest of inputflinger produce every
// Android event. Device classification, descriptors and key mapping follow
// stock EventHub (EventHub.cpp) so the reader observes the same device model.
#pragma once

#include <EventHub.h>
#include <input/Keyboard.h>
#include <input/PropertyMap.h>
#include <linux/input.h>

#include <bitset>
#include <condition_variable>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace android {

/** A seat device the hub presents to the reader. */
struct HostDeviceSpec {
    enum class Kind { KEYBOARD, MOUSE, TOUCHSCREEN, STYLUS };
    Kind kind;
    std::string name;
    /** Display size in pixels for absolute devices (touchscreen, stylus). */
    int32_t width = 0;
    int32_t height = 0;
    /** Stock KCM text for a keyboard; empty selects Generic.kcm. */
    std::string keyCharacterMap;
    /** A stylus tool's axes beyond position: kPressure, kDistance, kTilt. */
    int32_t axes = 0;
    static constexpr int32_t kPressure = 1, kDistance = 2, kTilt = 4;
};

class HostEventHub final : public EventHubInterface {
public:
    HostEventHub();
    ~HostEventHub() override;

    /** The instance the host reader factory created, or null before IMS starts. */
    static std::shared_ptr<HostEventHub> instance();

    // ----- Seat side (any thread)
    int32_t addDevice(const HostDeviceSpec& spec);
    void removeDevice(int32_t deviceId);
    /** Replaces a keyboard's character map, as a new layout from the compositor. */
    bool setKeyCharacterMap(int32_t deviceId, const std::string& contents);
    /** Queues one input_event; SYN_REPORT completes a packet and wakes the reader. */
    void event(int32_t deviceId, nsecs_t when, int32_t type, int32_t code, int32_t value);

    // ----- EventHubInterface
    ftl::Flags<InputDeviceClass> getDeviceClasses(int32_t deviceId) const override;
    InputDeviceIdentifier getDeviceIdentifier(int32_t deviceId) const override;
    int32_t getDeviceControllerNumber(int32_t deviceId) const override;
    std::optional<PropertyMap> getConfiguration(int32_t deviceId) const override;
    std::optional<RawAbsoluteAxisInfo> getAbsoluteAxisInfo(int32_t deviceId, int axis) const override;
    bool hasRelativeAxis(int32_t deviceId, int axis) const override;
    bool hasInputProperty(int32_t deviceId, int property) const override;
    bool hasMscEvent(int32_t deviceId, int mscEvent) const override;
    void setKeyRemapping(int32_t deviceId, const std::map<int32_t, int32_t>& keyRemapping) const override;
    status_t mapKey(int32_t deviceId, int32_t scanCode, int32_t usageCode, int32_t metaState,
                    int32_t* outKeycode, int32_t* outMetaState, uint32_t* outFlags) const override;
    status_t mapAxis(int32_t deviceId, int32_t scanCode, AxisInfo* outAxisInfo) const override;
    void setExcludedDevices(const std::vector<std::string>& devices) override;
    std::vector<RawEvent> getEvents(int timeoutMillis) override;
    std::vector<TouchVideoFrame> getVideoFrames(int32_t deviceId) override;
    base::Result<std::pair<InputDeviceSensorType, int32_t>> mapSensor(int32_t deviceId, int32_t absCode) const override;
    std::vector<int32_t> getRawBatteryIds(int32_t deviceId) const override;
    std::optional<RawBatteryInfo> getRawBatteryInfo(int32_t deviceId, int32_t batteryId) const override;
    std::vector<int32_t> getRawLightIds(int32_t deviceId) const override;
    std::optional<RawLightInfo> getRawLightInfo(int32_t deviceId, int32_t lightId) const override;
    std::optional<int32_t> getLightBrightness(int32_t deviceId, int32_t lightId) const override;
    void setLightBrightness(int32_t deviceId, int32_t lightId, int32_t brightness) override;
    std::optional<std::unordered_map<LightColor, int32_t>> getLightIntensities(int32_t deviceId, int32_t lightId) const override;
    void setLightIntensities(int32_t deviceId, int32_t lightId, std::unordered_map<LightColor, int32_t> intensities) override;
    std::optional<RawLayoutInfo> getRawLayoutInfo(int32_t deviceId) const override;
    int32_t getScanCodeState(int32_t deviceId, int32_t scanCode) const override;
    int32_t getKeyCodeState(int32_t deviceId, int32_t keyCode) const override;
    int32_t getSwitchState(int32_t deviceId, int32_t sw) const override;
    std::optional<int32_t> getAbsoluteAxisValue(int32_t deviceId, int32_t axis) const override;
    base::Result<std::vector<int32_t>> getMtSlotValues(int32_t deviceId, int32_t axis, size_t slotCount) const override;
    int32_t getKeyCodeForKeyLocation(int32_t deviceId, int32_t locationKeyCode) const override;
    bool markSupportedKeyCodes(int32_t deviceId, const std::vector<int32_t>& keyCodes, uint8_t* outFlags) const override;
    bool hasScanCode(int32_t deviceId, int32_t scanCode) const override;
    bool hasKeyCode(int32_t deviceId, int32_t keyCode) const override;
    bool hasLed(int32_t deviceId, int32_t led) const override;
    void setLedState(int32_t deviceId, int32_t led, bool on) override;
    void getVirtualKeyDefinitions(int32_t deviceId, std::vector<VirtualKeyDefinition>& outVirtualKeys) const override;
    const std::shared_ptr<KeyCharacterMap> getKeyCharacterMap(int32_t deviceId) const override;
    bool setKeyboardLayoutOverlay(int32_t deviceId, std::shared_ptr<KeyCharacterMap> map) override;
    void vibrate(int32_t deviceId, const VibrationElement& effect) override;
    void cancelVibrate(int32_t deviceId) override;
    std::vector<int32_t> getVibratorIds(int32_t deviceId) const override;
    std::optional<int32_t> getBatteryCapacity(int32_t deviceId, int32_t batteryId) const override;
    std::optional<int32_t> getBatteryStatus(int32_t deviceId, int32_t batteryId) const override;
    void requestReopenDevices() override;
    void wake() override;
    void dump(std::string& dump) const override;
    void monitor() const override;
    bool isDeviceEnabled(int32_t deviceId) const override;
    status_t enableDevice(int32_t deviceId) override;
    status_t disableDevice(int32_t deviceId) override;
    std::filesystem::path getSysfsRootPath(int32_t deviceId) const override;
    void sysfsNodeChanged(const std::string& sysfsNodePath) override;
    bool setKernelWakeEnabled(int32_t deviceId, bool enabled) override;

private:
    struct Device {
        int32_t id;
        HostDeviceSpec::Kind kind;
        InputDeviceIdentifier identifier;
        ftl::Flags<InputDeviceClass> classes;
        PropertyMap configuration;
        KeyMap keyMap;
        std::bitset<KEY_MAX + 1> keyBits;
        std::bitset<KEY_MAX + 1> keyState;
        std::bitset<REL_MAX + 1> relBits;
        std::bitset<ABS_MAX + 1> absBits;
        std::bitset<INPUT_PROP_MAX + 1> propBits;
        std::array<RawAbsoluteAxisInfo, ABS_MAX + 1> absInfo{};
        std::array<int32_t, ABS_MAX + 1> absValue{};
        bool enabled = true;

        bool hasKeycode(int32_t keycode) const;
        bool hasKeycodeInternal(int32_t keycode) const;
    };

    Device* deviceLocked(int32_t deviceId) const;
    void assignDescriptorLocked(InputDeviceIdentifier& identifier) const;
    void configureLocked(Device& device, const HostDeviceSpec& spec);

    mutable std::mutex mLock;
    std::condition_variable mWake;
    std::map<int32_t, std::unique_ptr<Device>> mDevices;
    std::deque<RawEvent> mPending;   // complete packets and device changes, in order
    std::map<int32_t, std::vector<RawEvent>> mPartial; // per device, until SYN_REPORT
    int32_t mNextDeviceId = 1;
    bool mWakeRequested = false;
};

} // namespace android
