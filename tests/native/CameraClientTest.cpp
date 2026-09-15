// Original CameraBase discovery and BpCameraService parcel contracts, with no capture HAL.
#include <cassert>
#include <camera/Camera.h>
#include <android/hardware/BnCameraService.h>
#include <binder/Binder.h>
#include <binder/IServiceManager.h>
#include <cutils/properties.h>
#include <iostream>

using namespace android;
using hardware::ICameraService;
using hardware::BnCameraService;

class CameraFixture final : public BBinder {
public:
    bool failCount = false;
    bool failInfo = false;
    int connections = 0;
    const String16& getInterfaceDescriptor() const override { return ICameraService::descriptor; }
    status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) override {
        if (code != BnCameraService::TRANSACTION_getNumberOfCameras &&
            code != BnCameraService::TRANSACTION_getCameraInfo &&
            code != BnCameraService::TRANSACTION_connect) return BBinder::onTransact(code, data, reply, flags);
        assert(data.checkInterface(this));
        assert(reply);
        if (code == BnCameraService::TRANSACTION_getNumberOfCameras) {
            assert(data.readInt32() == ICameraService::CAMERA_TYPE_BACKWARD_COMPATIBLE);
        } else {
            if (code == BnCameraService::TRANSACTION_connect) assert(data.readStrongBinder());
            assert(data.readInt32() == 1);
            if (code == BnCameraService::TRANSACTION_connect) assert(data.readInt32() == 35);
            assert(data.readInt32() == 0);
            if (code == BnCameraService::TRANSACTION_connect) { assert(!data.readBool()); ++connections; }
        }
        content::AttributionSourceState attribution;
        assert(data.readParcelable(&attribution) == OK);
        assert(attribution.uid == 12345 && attribution.pid == 23456);
        assert(data.readInt32() == 0 && data.dataAvail() == 0);
        if ((code == BnCameraService::TRANSACTION_getNumberOfCameras && failCount) ||
            (code == BnCameraService::TRANSACTION_getCameraInfo && failInfo) ||
            code == BnCameraService::TRANSACTION_connect)
            return binder::Status::fromServiceSpecificError(ICameraService::ERROR_DISCONNECTED).writeToParcel(reply);
        assert(binder::Status::ok().writeToParcel(reply) == OK);
        if (code == BnCameraService::TRANSACTION_getNumberOfCameras) return reply->writeInt32(2);
        hardware::CameraInfo info;
        info.facing = CAMERA_FACING_FRONT;
        info.orientation = 270;
        return reply->writeParcelable(info);
    }
};

int main() {
    content::AttributionSourceState attribution;
    attribution.uid = 12345;
    attribution.pid = 23456;
    assert(property_get_bool("config.disable_cameraservice", false));
    assert(Camera::getNumberOfCameras(attribution, 0) == 0);
    assert(Camera::connect(1, 35, 0, false, attribution, 0) == nullptr);
    hardware::CameraInfo info;
    assert(Camera::getCameraInfo(1, 0, attribution, 0, &info) == UNKNOWN_ERROR);
    auto service = sp<CameraFixture>::make();
    assert(defaultServiceManager()->addService(String16("media.camera"), service) == OK);
    sp<ICameraService> typed;
    assert(getService(String16("media.camera"), &typed) == OK && typed);
    assert(getService(String16("dev.artzig.camera.missing"), &typed) == NAME_NOT_FOUND && !typed);
    assert(property_set("config.disable_cameraservice", "false") == 0);
    assert(Camera::getNumberOfCameras(attribution, 0) == 2);
    assert(Camera::getCameraInfo(1, 0, attribution, 0, &info) == OK);
    assert(info.facing == CAMERA_FACING_FRONT && info.orientation == 270);
    assert(Camera::connect(1, 35, 0, false, attribution, 0) == nullptr);
    assert(service->connections == 1);
    service->failInfo = true;
    assert(Camera::getCameraInfo(1, 0, attribution, 0, &info) == ICameraService::ERROR_DISCONNECTED);
    service->failCount = true;
    assert(Camera::getNumberOfCameras(attribution, 0) == 0);
    std::cout << "PASS stock CameraBase: disabled discovery, Binder attribution/info and connection failures\n";
}
