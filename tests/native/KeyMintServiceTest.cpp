// The software KeyMint HAL service as keystore2 reaches it: the shared-secret
// negotiation keystore2 runs at boot, a secure-clock timestamp, and an AES key
// generated, used and verified through IKeyMintDevice and IKeyMintOperation.
#include <aidl/android/hardware/security/keymint/IKeyMintDevice.h>
#include <aidl/android/hardware/security/secureclock/ISecureClock.h>
#include <aidl/android/hardware/security/sharedsecret/ISharedSecret.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

using namespace aidl::android::hardware::security;
using keymint::Algorithm;
using keymint::BlockMode;
using keymint::KeyParameter;
using keymint::KeyParameterValue;
using keymint::KeyPurpose;
using keymint::PaddingMode;
using keymint::Tag;

namespace {

void check(const ndk::ScopedAStatus& status, const char* what) {
    if (status.isOk()) return;
    std::cerr << what << " failed: " << status.getDescription() << "\n";
    std::exit(1);
}

void expect(bool condition, const char* what) {
    if (condition) return;
    std::cerr << "expectation failed: " << what << "\n";
    std::exit(1);
}

template <typename T>
std::shared_ptr<T> service(const std::string& instance) {
    auto binder = ndk::SpAIBinder(AServiceManager_waitForService(instance.c_str()));
    expect(binder.get() != nullptr, "service registered");
    expect(AServiceManager_isDeclared(instance.c_str()), "service declared in the session manifest");
    return T::fromBinder(binder);
}

template <KeyParameterValue::Tag field, typename V>
KeyParameter parameter(Tag tag, V value) {
    return {.tag = tag, .value = KeyParameterValue::make<field>(value)};
}

} // namespace

int main() {
    ABinderProcess_startThreadPool();
    auto secret = service<sharedsecret::ISharedSecret>(std::string(sharedsecret::ISharedSecret::descriptor) + "/default");
    sharedsecret::SharedSecretParameters parameters;
    check(secret->getSharedSecretParameters(&parameters), "getSharedSecretParameters");
    std::vector<uint8_t> checksum;
    check(secret->computeSharedSecret({parameters}, &checksum), "computeSharedSecret");
    expect(!checksum.empty(), "shared secret checksum");

    auto clock = service<secureclock::ISecureClock>(std::string(secureclock::ISecureClock::descriptor) + "/default");
    secureclock::TimeStampToken token;
    check(clock->generateTimeStamp(42, &token), "generateTimeStamp");
    expect(token.challenge == 42 && !token.mac.empty(), "timestamp token");

    auto device = service<keymint::IKeyMintDevice>(std::string(keymint::IKeyMintDevice::descriptor) + "/default");
    keymint::KeyMintHardwareInfo info;
    check(device->getHardwareInfo(&info), "getHardwareInfo");
    std::cout << "keymint " << info.keyMintName << " by " << info.keyMintAuthorName << " version " << info.versionNumber
              << " security level " << keymint::toString(info.securityLevel) << "\n";

    std::vector<KeyParameter> key = {
        parameter<KeyParameterValue::algorithm>(Tag::ALGORITHM, Algorithm::AES),
        parameter<KeyParameterValue::integer>(Tag::KEY_SIZE, 128),
        parameter<KeyParameterValue::blockMode>(Tag::BLOCK_MODE, BlockMode::ECB),
        parameter<KeyParameterValue::paddingMode>(Tag::PADDING, PaddingMode::NONE),
        parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::ENCRYPT),
        parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::DECRYPT),
        parameter<KeyParameterValue::boolValue>(Tag::NO_AUTH_REQUIRED, true),
    };
    keymint::KeyCreationResult created;
    check(device->generateKey(key, std::nullopt, &created), "generateKey");
    expect(!created.keyBlob.empty(), "key blob");

    const std::vector<uint8_t> plain = {'k', 'e', 'y', 's', 't', 'o', 'r', 'e', '2', ' ', 'k', 'e', 'y', 'm', 'i', 'n'};
    std::vector<KeyParameter> use = {
        parameter<KeyParameterValue::blockMode>(Tag::BLOCK_MODE, BlockMode::ECB),
        parameter<KeyParameterValue::paddingMode>(Tag::PADDING, PaddingMode::NONE),
    };
    auto run = [&](KeyPurpose purpose, const std::vector<uint8_t>& input) {
        keymint::BeginResult begin;
        check(device->begin(purpose, created.keyBlob, use, std::nullopt, &begin), "begin");
        std::vector<uint8_t> output;
        check(begin.operation->finish(input, std::nullopt, std::nullopt, std::nullopt, std::nullopt, &output), "finish");
        return output;
    };
    auto cipher = run(KeyPurpose::ENCRYPT, plain);
    expect(cipher.size() == plain.size() && cipher != plain, "AES-ECB ciphertext");
    expect(run(KeyPurpose::DECRYPT, cipher) == plain, "AES-ECB round trip");
    std::cout << "PASS keymint service\n";
    return 0;
}
