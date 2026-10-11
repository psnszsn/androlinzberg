// The stock IMS JNI pokes user activity through PowerManagerService's JNI
// (com_android_server_power_PowerManagerService.cpp), whose Java peer is not
// compiled while the session runs HostedPowerManagerService. That service has
// no screen-off timeout on a desktop and its userActivity is a no-op, so the
// stock JNI's call has no observable effect here. Source note: stock
// PowerManagerService and its JNI replace this definition when they land.
#include <com_android_server_power_PowerManagerService.h>

namespace android {

void android_server_PowerManagerService_userActivity(nsecs_t, int32_t, ui::LogicalDisplayId) {}

} // namespace android
