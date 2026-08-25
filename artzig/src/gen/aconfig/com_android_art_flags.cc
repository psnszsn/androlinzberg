#include "com_android_art_flags.h"





namespace com::android::art::flags {

class flag_provider : public flag_provider_interface {
    public:

        
        virtual bool always_enable_profile_code() override {
            return COM_ANDROID_ART_FLAGS_ALWAYS_ENABLE_PROFILE_CODE;
        }
        virtual bool art_service_v3() override {
            return COM_ANDROID_ART_FLAGS_ART_SERVICE_V3;
        }
        virtual bool executable_method_file_offsets() override {
            return COM_ANDROID_ART_FLAGS_EXECUTABLE_METHOD_FILE_OFFSETS;
        }
        virtual bool executable_method_file_offsets_v2() override {
            return COM_ANDROID_ART_FLAGS_EXECUTABLE_METHOD_FILE_OFFSETS_V2;
        }
        virtual bool fast_baseline_compiler() override {
            return COM_ANDROID_ART_FLAGS_FAST_BASELINE_COMPILER;
        }
        virtual bool test() override {
            return COM_ANDROID_ART_FLAGS_TEST;
        }
        virtual bool use_generational_cmc() override {
            return COM_ANDROID_ART_FLAGS_USE_GENERATIONAL_CMC;
        }
        virtual bool virtual_thread_impl_v1() override {
            return COM_ANDROID_ART_FLAGS_VIRTUAL_THREAD_IMPL_V1;
        }

    

    };

std::unique_ptr<flag_provider_interface> provider_ =
    std::make_unique<flag_provider>();
}


bool com_android_art_flags_always_enable_profile_code() {
    return COM_ANDROID_ART_FLAGS_ALWAYS_ENABLE_PROFILE_CODE;
}

bool com_android_art_flags_art_service_v3() {
    return COM_ANDROID_ART_FLAGS_ART_SERVICE_V3;
}

bool com_android_art_flags_executable_method_file_offsets() {
    return COM_ANDROID_ART_FLAGS_EXECUTABLE_METHOD_FILE_OFFSETS;
}

bool com_android_art_flags_executable_method_file_offsets_v2() {
    return COM_ANDROID_ART_FLAGS_EXECUTABLE_METHOD_FILE_OFFSETS_V2;
}

bool com_android_art_flags_fast_baseline_compiler() {
    return COM_ANDROID_ART_FLAGS_FAST_BASELINE_COMPILER;
}

bool com_android_art_flags_test() {
    return COM_ANDROID_ART_FLAGS_TEST;
}

bool com_android_art_flags_use_generational_cmc() {
    return COM_ANDROID_ART_FLAGS_USE_GENERATIONAL_CMC;
}

bool com_android_art_flags_virtual_thread_impl_v1() {
    return COM_ANDROID_ART_FLAGS_VIRTUAL_THREAD_IMPL_V1;
}

