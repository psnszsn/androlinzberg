#define LOG_TAG "HostEventHub"

#include "HostEventHub.h"

#include <KeyCodeClassifications.h>
#include <android-base/stringprintf.h>
#include <input/InputDevice.h>
#include <input/KeyCharacterMap.h>
#include <input/KeyLayoutMap.h>
#include <log/log.h>
#include <openssl/sha.h>
#include <utils/Timers.h>

#include <algorithm>
#include <chrono>

namespace android {

using base::StringPrintf;
using namespace ftl::flag_operators;

namespace {

std::mutex gInstanceLock;
std::weak_ptr<HostEventHub> gInstance;

// EventHub.cpp's descriptor: the identity the framework persists keyboard
// layouts and device settings under.
std::string sha1(const std::string& in) {
    SHA_CTX ctx;
    SHA1_Init(&ctx);
    SHA1_Update(&ctx, reinterpret_cast<const u_char*>(in.c_str()), in.size());
    u_char digest[SHA_DIGEST_LENGTH];
    SHA1_Final(digest, &ctx);
    std::string out;
    for (size_t i = 0; i < SHA_DIGEST_LENGTH; i++) out += StringPrintf("%02x", digest[i]);
    return out;
}

std::string generateDescriptor(InputDeviceIdentifier& identifier) {
    std::string raw = StringPrintf(":%04x:%04x:", identifier.vendor, identifier.product);
    if (!identifier.uniqueId.empty()) raw += "uniqueId:" + identifier.uniqueId;
    if (identifier.nonce != 0) raw += StringPrintf("nonce:%04x", identifier.nonce);
    if (identifier.vendor == 0 && identifier.product == 0) {
        if (!identifier.name.empty()) {
            raw += "name:" + identifier.name;
        } else if (!identifier.location.empty()) {
            raw += "location:" + identifier.location;
        }
    }
    identifier.descriptor = sha1(raw);
    return raw;
}

RawAbsoluteAxisInfo axis(int32_t min, int32_t max, int32_t resolution = 0) {
    RawAbsoluteAxisInfo info;
    info.minValue = min;
    info.maxValue = max;
    info.resolution = resolution;
    return info;
}

} // namespace

std::shared_ptr<HostEventHub> HostEventHub::instance() {
    std::scoped_lock lock(gInstanceLock);
    return gInstance.lock();
}

// EventHub::createVirtualKeyboardLocked, which every scan provides: the
// device KeyCharacterMap.load(VIRTUAL_KEYBOARD) resolves (Virtual.kcm).
HostEventHub::HostEventHub() {
    std::scoped_lock lock(mLock);
    auto device = std::make_unique<Device>();
    device->id = ReservedInputDeviceId::VIRTUAL_KEYBOARD_ID;
    device->kind = HostDeviceSpec::Kind::KEYBOARD;
    device->identifier.name = "Virtual";
    device->identifier.uniqueId = "<virtual>";
    assignDescriptorLocked(device->identifier);
    device->classes = InputDeviceClass::KEYBOARD | InputDeviceClass::ALPHAKEY |
            InputDeviceClass::DPAD | InputDeviceClass::VIRTUAL;
    device->keyMap.load(device->identifier, nullptr);
    RawEvent added{};
    added.when = systemTime(SYSTEM_TIME_MONOTONIC);
    added.deviceId = device->id;
    added.type = DEVICE_ADDED;
    mPending.push_back(added);
    mDevices.emplace(device->id, std::move(device));
}
HostEventHub::~HostEventHub() = default;

// ----- Seat side

int32_t HostEventHub::addDevice(const HostDeviceSpec& spec) {
    std::scoped_lock lock(mLock);
    auto device = std::make_unique<Device>();
    device->id = mNextDeviceId++;
    configureLocked(*device, spec);
    const int32_t id = device->id;
    mDevices.emplace(id, std::move(device));
    RawEvent added{};
    added.when = systemTime(SYSTEM_TIME_MONOTONIC);
    added.deviceId = id;
    added.type = DEVICE_ADDED;
    mPending.push_back(added);
    mWake.notify_all();
    return id;
}

void HostEventHub::removeDevice(int32_t deviceId) {
    std::scoped_lock lock(mLock);
    if (mDevices.erase(deviceId) == 0) return;
    mPartial.erase(deviceId);
    RawEvent removed{};
    removed.when = systemTime(SYSTEM_TIME_MONOTONIC);
    removed.deviceId = deviceId;
    removed.type = DEVICE_REMOVED;
    mPending.push_back(removed);
    mWake.notify_all();
}

bool HostEventHub::setKeyCharacterMap(int32_t deviceId, const std::string& contents) {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || device->kind != HostDeviceSpec::Kind::KEYBOARD) return false;
    auto map = KeyCharacterMap::loadContents("<compositor keymap>", contents.c_str(),
                                             KeyCharacterMap::Format::BASE);
    if (!map.ok()) {
        ALOGE("Rejected compositor keymap for device %d: %s", deviceId,
              map.error().message().c_str());
        return false;
    }
    device->keyMap.keyCharacterMap = *map;
    device->keyMap.keyCharacterMapFile = "<compositor keymap>";
    // InputReader re-reads the map on its next configuration of the device;
    // a re-add is how stock EventHub reports a changed keyboard.
    RawEvent removed{}, added{};
    removed.when = added.when = systemTime(SYSTEM_TIME_MONOTONIC);
    removed.deviceId = added.deviceId = deviceId;
    removed.type = DEVICE_REMOVED;
    added.type = DEVICE_ADDED;
    mPending.push_back(removed);
    mPending.push_back(added);
    mWake.notify_all();
    return true;
}

void HostEventHub::event(int32_t deviceId, nsecs_t when, int32_t type, int32_t code, int32_t value) {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || !device->enabled) return;
    RawEvent raw{};
    raw.when = when;
    raw.readTime = systemTime(SYSTEM_TIME_MONOTONIC);
    raw.deviceId = deviceId;
    raw.type = type;
    raw.code = code;
    raw.value = value;
    auto& partial = mPartial[deviceId];
    partial.push_back(raw);
    if (type == EV_SYN && code == SYN_REPORT) {
        mPending.insert(mPending.end(), partial.begin(), partial.end());
        partial.clear();
        mWake.notify_all();
    }
}

void HostEventHub::configureLocked(Device& device, const HostDeviceSpec& spec) {
    device.kind = spec.kind;
    InputDeviceIdentifier& identifier = device.identifier;
    identifier.name = spec.name;
    // Each seat capability is its own device: InputReader merges devices
    // whose bus, ids and location agree (isSubDevice). The location is also
    // the input port the session associates with its display.
    switch (spec.kind) {
        case HostDeviceSpec::Kind::KEYBOARD: identifier.location = "wayland-seat0/keyboard"; break;
        case HostDeviceSpec::Kind::MOUSE: identifier.location = "wayland-seat0/pointer"; break;
        case HostDeviceSpec::Kind::TOUCHSCREEN: identifier.location = "wayland-seat0/touch"; break;
        case HostDeviceSpec::Kind::STYLUS:
            // Tools share the tablet's port; their unique ids keep them apart.
            identifier.location = "wayland-seat0/tablet";
            identifier.uniqueId = spec.name;
            break;
    }
    identifier.bus = BUS_VIRTUAL;
    switch (spec.kind) {
        case HostDeviceSpec::Kind::KEYBOARD: {
            // Every key position the compositor's keymap can produce.
            for (int32_t code = KEY_ESC; code < BTN_MISC; code++) device.keyBits.set(code);
            for (int32_t code = KEY_OK; code <= KEY_MAX; code++) device.keyBits.set(code);
            device.classes = InputDeviceClass::KEYBOARD | InputDeviceClass::EXTERNAL;
            break;
        }
        case HostDeviceSpec::Kind::MOUSE:
            for (int32_t code : {REL_X, REL_Y, REL_WHEEL, REL_HWHEEL, REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES})
                device.relBits.set(code);
            for (int32_t code : {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, BTN_SIDE, BTN_EXTRA, BTN_FORWARD, BTN_BACK})
                device.keyBits.set(code);
            device.classes = InputDeviceClass::CURSOR | InputDeviceClass::EXTERNAL;
            device.configuration.addProperty("cursor.mode", "pointer");
            break;
        case HostDeviceSpec::Kind::TOUCHSCREEN:
            device.absBits.set(ABS_MT_SLOT);
            device.absInfo[ABS_MT_SLOT] = axis(0, 9);
            device.absBits.set(ABS_MT_TRACKING_ID);
            device.absInfo[ABS_MT_TRACKING_ID] = axis(0, 65535);
            device.absBits.set(ABS_MT_POSITION_X);
            device.absInfo[ABS_MT_POSITION_X] = axis(0, std::max(spec.width - 1, 0));
            device.absBits.set(ABS_MT_POSITION_Y);
            device.absInfo[ABS_MT_POSITION_Y] = axis(0, std::max(spec.height - 1, 0));
            device.absBits.set(ABS_MT_TOUCH_MAJOR);
            device.absInfo[ABS_MT_TOUCH_MAJOR] = axis(0, std::max(spec.width, spec.height));
            device.absBits.set(ABS_MT_TOUCH_MINOR);
            device.absInfo[ABS_MT_TOUCH_MINOR] = axis(0, std::max(spec.width, spec.height));
            device.absBits.set(ABS_MT_ORIENTATION);
            device.absInfo[ABS_MT_ORIENTATION] = axis(-90, 90);
            // The seat cancels a contact as a palm (TouchInputMapper's cancel path).
            device.absBits.set(ABS_MT_TOOL_TYPE);
            device.absInfo[ABS_MT_TOOL_TYPE] = axis(0, MT_TOOL_MAX);
            device.keyBits.set(BTN_TOUCH);
            device.propBits.set(INPUT_PROP_DIRECT);
            device.classes = InputDeviceClass::TOUCH | InputDeviceClass::TOUCH_MT;
            device.configuration.addProperty("touch.deviceType", "touchScreen");
            device.configuration.addProperty("touch.orientationAware", "1");
            // Contact axes are display pixels, the same units as the position.
            device.configuration.addProperty("touch.size.calibration", "geometric");
            device.configuration.addProperty("touch.orientation.calibration", "interpolated");
            device.configuration.addProperty("device.internal", "1");
            break;
        case HostDeviceSpec::Kind::STYLUS:
            // A tool reports absolute positions on the task surface, so it is a
            // pen display over the Android display: TouchInputMapper's direct
            // mode with stylus, eraser and mouse/lens tools, associated with
            // the session display through its input port.
            device.absBits.set(ABS_X);
            device.absInfo[ABS_X] = axis(0, std::max(spec.width - 1, 0));
            device.absBits.set(ABS_Y);
            device.absInfo[ABS_Y] = axis(0, std::max(spec.height - 1, 0));
            // A tool reports only the axes it has, as its evdev node would.
            if (spec.axes & HostDeviceSpec::kPressure) {
                device.absBits.set(ABS_PRESSURE);
                device.absInfo[ABS_PRESSURE] = axis(0, 65535);
            }
            if (spec.axes & HostDeviceSpec::kDistance) {
                device.absBits.set(ABS_DISTANCE);
                device.absInfo[ABS_DISTANCE] = axis(0, 65535);
            }
            if (spec.axes & HostDeviceSpec::kTilt) {
                device.absBits.set(ABS_TILT_X);
                device.absInfo[ABS_TILT_X] = axis(-90, 90);
                device.absBits.set(ABS_TILT_Y);
                device.absInfo[ABS_TILT_Y] = axis(-90, 90);
            }
            for (int32_t code = BTN_TOOL_PEN; code <= BTN_TOOL_LENS; code++) device.keyBits.set(code);
            for (int32_t code : {BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2}) device.keyBits.set(code);
            for (int32_t code = BTN_LEFT; code <= BTN_BACK; code++) device.keyBits.set(code);
            device.propBits.set(INPUT_PROP_DIRECT);
            device.classes = InputDeviceClass::TOUCH | InputDeviceClass::EXTERNAL;
            device.configuration.addProperty("touch.deviceType", "touchScreen");
            break;
    }
    assignDescriptorLocked(identifier);
    if (device.classes.test(InputDeviceClass::KEYBOARD)) {
        // EventHub::openDeviceLocked: load the key layout and map, then
        // classify alphabetic, D-pad and gamepad keyboards from them.
        device.keyMap.load(identifier, &device.configuration);
        if (!spec.keyCharacterMap.empty()) {
            auto map = KeyCharacterMap::loadContents("<compositor keymap>", spec.keyCharacterMap.c_str(),
                                                     KeyCharacterMap::Format::BASE);
            if (map.ok()) {
                device.keyMap.keyCharacterMap = *map;
                device.keyMap.keyCharacterMapFile = "<compositor keymap>";
            } else {
                ALOGE("Rejected compositor keymap for %s: %s", spec.name.c_str(),
                      map.error().message().c_str());
            }
        }
        if (device.hasKeycode(AKEYCODE_Q)) device.classes |= InputDeviceClass::ALPHAKEY;
        if (std::all_of(DPAD_REQUIRED_KEYCODES.begin(), DPAD_REQUIRED_KEYCODES.end(),
                        [&](int32_t keycode) { return device.hasKeycode(keycode); })) {
            device.classes |= InputDeviceClass::DPAD;
        }
    }
}

void HostEventHub::assignDescriptorLocked(InputDeviceIdentifier& identifier) const {
    identifier.nonce = 0;
    generateDescriptor(identifier);
    auto taken = [&] {
        return std::any_of(mDevices.begin(), mDevices.end(), [&](const auto& entry) {
            return entry.second->identifier.descriptor == identifier.descriptor;
        });
    };
    while (taken()) {
        identifier.nonce++;
        generateDescriptor(identifier);
    }
}

HostEventHub::Device* HostEventHub::deviceLocked(int32_t deviceId) const {
    auto it = mDevices.find(deviceId);
    return it == mDevices.end() ? nullptr : it->second.get();
}

bool HostEventHub::Device::hasKeycodeInternal(int32_t keycode) const {
    if (!keyMap.haveKeyLayout()) return false;
    for (int32_t scanCode : keyMap.keyLayoutMap->findScanCodesForKey(keycode)) {
        if (scanCode >= 0 && scanCode <= KEY_MAX && keyBits.test(scanCode)) return true;
    }
    return false;
}

bool HostEventHub::Device::hasKeycode(int32_t keycode) const {
    if (hasKeycodeInternal(keycode)) return true;
    if (keyMap.keyCharacterMap == nullptr) return false;
    for (int32_t from : keyMap.keyCharacterMap->findKeyCodesMappedToKeyCode(keycode)) {
        if (hasKeycodeInternal(from)) return true;
    }
    return false;
}

// ----- EventHubInterface

ftl::Flags<InputDeviceClass> HostEventHub::getDeviceClasses(int32_t deviceId) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device == nullptr ? ftl::Flags<InputDeviceClass>(0) : device->classes;
}

InputDeviceIdentifier HostEventHub::getDeviceIdentifier(int32_t deviceId) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device == nullptr ? InputDeviceIdentifier() : device->identifier;
}

int32_t HostEventHub::getDeviceControllerNumber(int32_t) const {
    return 0;
}

std::optional<PropertyMap> HostEventHub::getConfiguration(int32_t deviceId) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr) return std::nullopt;
    return device->configuration;
}

std::optional<RawAbsoluteAxisInfo> HostEventHub::getAbsoluteAxisInfo(int32_t deviceId, int axis) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || axis < 0 || axis > ABS_MAX || !device->absBits.test(axis)) return std::nullopt;
    return device->absInfo[axis];
}

bool HostEventHub::hasRelativeAxis(int32_t deviceId, int axis) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device != nullptr && axis >= 0 && axis <= REL_MAX && device->relBits.test(axis);
}

bool HostEventHub::hasInputProperty(int32_t deviceId, int property) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device != nullptr && property >= 0 && property <= INPUT_PROP_MAX && device->propBits.test(property);
}

bool HostEventHub::hasMscEvent(int32_t, int) const {
    return false;
}

void HostEventHub::setKeyRemapping(int32_t deviceId, const std::map<int32_t, int32_t>& keyRemapping) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device != nullptr && device->keyMap.keyCharacterMap) {
        device->keyMap.keyCharacterMap->setKeyRemapping(keyRemapping);
    }
}

// EventHub::mapKey.
status_t HostEventHub::mapKey(int32_t deviceId, int32_t scanCode, int32_t usageCode, int32_t metaState,
                              int32_t* outKeycode, int32_t* outMetaState, uint32_t* outFlags) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    status_t status = NAME_NOT_FOUND;
    if (device != nullptr) {
        const std::shared_ptr<KeyCharacterMap> kcm = device->keyMap.keyCharacterMap;
        if (kcm && !kcm->mapKey(scanCode, usageCode, outKeycode)) {
            *outFlags = 0;
            status = NO_ERROR;
        }
        if (status != NO_ERROR && device->keyMap.haveKeyLayout() &&
            !device->keyMap.keyLayoutMap->mapKey(scanCode, usageCode, outKeycode, outFlags)) {
            status = NO_ERROR;
        }
        if (status == NO_ERROR) {
            if (kcm) {
                *outKeycode = kcm->applyKeyRemapping(*outKeycode);
                std::tie(*outKeycode, *outMetaState) = kcm->applyKeyBehavior(*outKeycode, metaState);
            } else {
                *outMetaState = metaState;
            }
        }
    }
    if (status != NO_ERROR) {
        *outKeycode = 0;
        *outFlags = 0;
        *outMetaState = metaState;
    }
    return status;
}

status_t HostEventHub::mapAxis(int32_t deviceId, int32_t scanCode, AxisInfo* outAxisInfo) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || !device->keyMap.haveKeyLayout()) return NAME_NOT_FOUND;
    std::optional<AxisInfo> info = device->keyMap.keyLayoutMap->mapAxis(scanCode);
    if (!info) return NAME_NOT_FOUND;
    *outAxisInfo = *info;
    return NO_ERROR;
}

void HostEventHub::setExcludedDevices(const std::vector<std::string>&) {
    // Exclusion names configured devices by name (config_excludedDevices);
    // the seat's devices are never listed there.
}

std::vector<RawEvent> HostEventHub::getEvents(int timeoutMillis) {
    std::unique_lock lock(mLock);
    auto ready = [this] { return !mPending.empty() || mWakeRequested; };
    if (timeoutMillis < 0) {
        mWake.wait(lock, ready);
    } else {
        mWake.wait_for(lock, std::chrono::milliseconds(timeoutMillis), ready);
    }
    mWakeRequested = false;
    // EventHub::getEvents reports device additions and removals on their own,
    // before any later input: InputReader announces a new device only after
    // the batch that added it, so its first packets must follow in another.
    auto deviceChange = [](const RawEvent& event) {
        return event.type == DEVICE_ADDED || event.type == DEVICE_REMOVED;
    };
    const bool changes = !mPending.empty() && deviceChange(mPending.front());
    auto end = std::find_if(mPending.begin(), mPending.end(),
                            [&](const RawEvent& event) { return deviceChange(event) != changes; });
    std::vector<RawEvent> events(mPending.begin(), end);
    mPending.erase(mPending.begin(), end);
    // A device's key and axis state is what the reader has read of it: mappers
    // reset their accumulators from it (EVIOCGKEY/EVIOCGABS on a device), so it
    // must not run ahead of packets still queued.
    for (const RawEvent& event : events) {
        Device* device = deviceLocked(event.deviceId);
        if (device == nullptr) continue;
        if (event.type == EV_KEY && event.code >= 0 && event.code <= KEY_MAX)
            device->keyState.set(event.code, event.value != 0);
        if (event.type == EV_ABS && event.code >= 0 && event.code <= ABS_MAX)
            device->absValue[event.code] = event.value;
    }
    return events;
}

std::vector<TouchVideoFrame> HostEventHub::getVideoFrames(int32_t) {
    return {};
}

base::Result<std::pair<InputDeviceSensorType, int32_t>> HostEventHub::mapSensor(int32_t, int32_t absCode) const {
    return Errorf("Device has no sensor for absCode {}", absCode);
}

std::vector<int32_t> HostEventHub::getRawBatteryIds(int32_t) const {
    return {};
}

std::optional<RawBatteryInfo> HostEventHub::getRawBatteryInfo(int32_t, int32_t) const {
    return std::nullopt;
}

std::vector<int32_t> HostEventHub::getRawLightIds(int32_t) const {
    return {};
}

std::optional<RawLightInfo> HostEventHub::getRawLightInfo(int32_t, int32_t) const {
    return std::nullopt;
}

std::optional<int32_t> HostEventHub::getLightBrightness(int32_t, int32_t) const {
    return std::nullopt;
}

void HostEventHub::setLightBrightness(int32_t, int32_t, int32_t) {}

std::optional<std::unordered_map<LightColor, int32_t>> HostEventHub::getLightIntensities(int32_t, int32_t) const {
    return std::nullopt;
}

void HostEventHub::setLightIntensities(int32_t, int32_t, std::unordered_map<LightColor, int32_t>) {}

std::optional<RawLayoutInfo> HostEventHub::getRawLayoutInfo(int32_t) const {
    return std::nullopt;
}

int32_t HostEventHub::getScanCodeState(int32_t deviceId, int32_t scanCode) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || scanCode < 0 || scanCode > KEY_MAX) return AKEY_STATE_UNKNOWN;
    return device->keyState.test(scanCode) ? AKEY_STATE_DOWN : AKEY_STATE_UP;
}

// EventHub::getKeyCodeState.
int32_t HostEventHub::getKeyCodeState(int32_t deviceId, int32_t keyCode) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || !device->keyMap.haveKeyLayout()) return AKEY_STATE_UNKNOWN;
    const std::vector<int32_t> scanCodes = device->keyMap.keyLayoutMap->findScanCodesForKey(keyCode);
    if (scanCodes.empty()) return AKEY_STATE_UNKNOWN;
    return std::any_of(scanCodes.begin(), scanCodes.end(),
                       [device](int32_t sc) { return sc >= 0 && sc <= KEY_MAX && device->keyState.test(sc); })
            ? AKEY_STATE_DOWN
            : AKEY_STATE_UP;
}

int32_t HostEventHub::getSwitchState(int32_t, int32_t) const {
    return AKEY_STATE_UNKNOWN;
}

std::optional<int32_t> HostEventHub::getAbsoluteAxisValue(int32_t deviceId, int32_t axis) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || axis < 0 || axis > ABS_MAX || !device->absBits.test(axis)) return std::nullopt;
    return device->absValue[axis];
}

base::Result<std::vector<int32_t>> HostEventHub::getMtSlotValues(int32_t deviceId, int32_t axis, size_t slotCount) const {
    // EVIOCGMTSLOTS seeds a reconfigured multi-touch mapper with the slots
    // already down. The seat re-sends each contact after a device is added.
    std::vector<int32_t> values(slotCount + 1, 0);
    values[0] = axis;
    if (axis == ABS_MT_TRACKING_ID) std::fill(values.begin() + 1, values.end(), -1);
    (void)deviceId;
    return values;
}

// EventHub::getKeyCodeForKeyLocation.
int32_t HostEventHub::getKeyCodeForKeyLocation(int32_t deviceId, int32_t locationKeyCode) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || device->keyMap.keyCharacterMap == nullptr || device->keyMap.keyLayoutMap == nullptr) {
        return AKEYCODE_UNKNOWN;
    }
    std::vector<int32_t> scanCodes = device->keyMap.keyLayoutMap->findScanCodesForKey(locationKeyCode);
    if (scanCodes.empty()) return AKEYCODE_UNKNOWN;
    int32_t outKeyCode;
    switch (device->keyMap.keyCharacterMap->mapKey(scanCodes[0], /*usageCode=*/0, &outKeyCode)) {
        case OK:
            break;
        case NAME_NOT_FOUND:
            outKeyCode = locationKeyCode;
            break;
        default:
            outKeyCode = AKEYCODE_UNKNOWN;
            break;
    }
    return device->keyMap.keyCharacterMap->applyKeyRemapping(outKeyCode);
}

bool HostEventHub::markSupportedKeyCodes(int32_t deviceId, const std::vector<int32_t>& keyCodes, uint8_t* outFlags) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || !device->keyMap.haveKeyLayout()) return false;
    for (size_t i = 0; i < keyCodes.size(); i++) {
        if (device->hasKeycode(keyCodes[i])) outFlags[i] = 1;
    }
    return true;
}

bool HostEventHub::hasScanCode(int32_t deviceId, int32_t scanCode) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device != nullptr && scanCode >= 0 && scanCode <= KEY_MAX && device->keyBits.test(scanCode);
}

bool HostEventHub::hasKeyCode(int32_t deviceId, int32_t keyCode) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device != nullptr && device->hasKeycode(keyCode);
}

bool HostEventHub::hasLed(int32_t, int32_t) const {
    // Lock indicators belong to the compositor's keyboard, not to Android.
    return false;
}

void HostEventHub::setLedState(int32_t, int32_t, bool) {}

void HostEventHub::getVirtualKeyDefinitions(int32_t, std::vector<VirtualKeyDefinition>& outVirtualKeys) const {
    outVirtualKeys.clear();
}

const std::shared_ptr<KeyCharacterMap> HostEventHub::getKeyCharacterMap(int32_t deviceId) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device == nullptr ? nullptr : device->keyMap.keyCharacterMap;
}

// EventHub::setKeyboardLayoutOverlay.
bool HostEventHub::setKeyboardLayoutOverlay(int32_t deviceId, std::shared_ptr<KeyCharacterMap> map) {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr || device->keyMap.keyCharacterMap == nullptr) return false;
    if (map == nullptr) {
        device->keyMap.keyCharacterMap->clearLayoutOverlay();
        return true;
    }
    device->keyMap.keyCharacterMap->combine(*map);
    return true;
}

void HostEventHub::vibrate(int32_t, const VibrationElement&) {}
void HostEventHub::cancelVibrate(int32_t) {}

std::vector<int32_t> HostEventHub::getVibratorIds(int32_t) const {
    return {};
}

std::optional<int32_t> HostEventHub::getBatteryCapacity(int32_t, int32_t) const {
    return std::nullopt;
}

std::optional<int32_t> HostEventHub::getBatteryStatus(int32_t, int32_t) const {
    return std::nullopt;
}

void HostEventHub::requestReopenDevices() {
    // EventHub closes and rescans /dev/input. The seat's devices have no
    // descriptors to reopen; report each as removed and added again.
    std::scoped_lock lock(mLock);
    const nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    for (const auto& [id, device] : mDevices) {
        RawEvent removed{}, added{};
        removed.when = added.when = now;
        removed.deviceId = added.deviceId = id;
        removed.type = DEVICE_REMOVED;
        added.type = DEVICE_ADDED;
        mPending.push_back(removed);
        mPending.push_back(added);
    }
    mWake.notify_all();
}

void HostEventHub::wake() {
    std::scoped_lock lock(mLock);
    mWakeRequested = true;
    mWake.notify_all();
}

void HostEventHub::dump(std::string& dump) const {
    std::scoped_lock lock(mLock);
    dump += "Host Event Hub State:\n";
    for (const auto& [id, device] : mDevices) {
        dump += StringPrintf("  %d: %s\n    Classes: %s\n    Descriptor: %s\n    KeyLayoutFile: %s\n"
                             "    KeyCharacterMapFile: %s\n",
                             id, device->identifier.name.c_str(), device->classes.string().c_str(),
                             device->identifier.descriptor.c_str(), device->keyMap.keyLayoutFile.c_str(),
                             device->keyMap.keyCharacterMapFile.c_str());
    }
}

void HostEventHub::monitor() const {
    std::unique_lock lock(mLock);
}

bool HostEventHub::isDeviceEnabled(int32_t deviceId) const {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    return device != nullptr && device->enabled;
}

status_t HostEventHub::enableDevice(int32_t deviceId) {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr) return BAD_VALUE;
    device->enabled = true;
    return OK;
}

status_t HostEventHub::disableDevice(int32_t deviceId) {
    std::scoped_lock lock(mLock);
    Device* device = deviceLocked(deviceId);
    if (device == nullptr) return BAD_VALUE;
    device->enabled = false;
    mPartial.erase(deviceId);
    return OK;
}

std::filesystem::path HostEventHub::getSysfsRootPath(int32_t) const {
    return {};
}

void HostEventHub::sysfsNodeChanged(const std::string&) {}

bool HostEventHub::setKernelWakeEnabled(int32_t, bool) {
    return false;
}

// ----- Factory

std::shared_ptr<HostEventHub> createHostEventHub() {
    auto hub = std::make_shared<HostEventHub>();
    std::scoped_lock lock(gInstanceLock);
    gInstance = hub;
    return hub;
}

} // namespace android
