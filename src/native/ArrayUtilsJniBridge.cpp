// ConfigInfrastructure's jarjar copy uses the same pinned ArrayUtils natives.
// Include the original implementation so both packages share its exact JNI
// table and cache-flushing zeroization, without copying the algorithm.
#include "com_android_internal_util_ArrayUtils.cpp"

extern "C" int azk_register_array_utils_packages(JNIEnv* env) {
    int result = android::register_com_android_internal_util_ArrayUtils(env);
    if (result != 0) return result;
    return jniRegisterNativeMethods(env,
            "com/android/internal/util/configinfrastructure/ArrayUtils",
            android::sMethods, NELEM(android::sMethods));
}
