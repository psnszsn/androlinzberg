// Stock keystore2 over the software KeyMint daemon, as an AndroidKeyStore
// client reaches it: an app-domain AES key generated through the TEE security
// level, used for an encrypt/decrypt round trip, listed and deleted.
#include <aidl/android/system/keystore2/IKeystoreService.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

using namespace aidl::android::hardware::security::keymint;
using namespace aidl::android::system::keystore2;

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

template <KeyParameterValue::Tag field, typename V>
KeyParameter parameter(Tag tag, V value) {
    return {.tag = tag, .value = KeyParameterValue::make<field>(value)};
}

} // namespace

int main() {
    ABinderProcess_startThreadPool();
    const std::string instance = std::string(IKeystoreService::descriptor) + "/default";
    auto binder = ndk::SpAIBinder(AServiceManager_waitForService(instance.c_str()));
    expect(binder.get() != nullptr, "keystore2 registered");
    auto keystore = IKeystoreService::fromBinder(binder);

    std::shared_ptr<IKeystoreSecurityLevel> tee;
    check(keystore->getSecurityLevel(SecurityLevel::TRUSTED_ENVIRONMENT, &tee), "getSecurityLevel");

    const KeyDescriptor alias{.domain = Domain::APP, .nspace = -1, .alias = "azk-keystore2-test", .blob = std::nullopt};
    std::vector<KeyParameter> generation = {
        parameter<KeyParameterValue::algorithm>(Tag::ALGORITHM, Algorithm::AES),
        parameter<KeyParameterValue::integer>(Tag::KEY_SIZE, 256),
        parameter<KeyParameterValue::blockMode>(Tag::BLOCK_MODE, BlockMode::GCM),
        parameter<KeyParameterValue::paddingMode>(Tag::PADDING, PaddingMode::NONE),
        parameter<KeyParameterValue::integer>(Tag::MIN_MAC_LENGTH, 128),
        parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::ENCRYPT),
        parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::DECRYPT),
        parameter<KeyParameterValue::boolValue>(Tag::NO_AUTH_REQUIRED, true),
    };
    KeyMetadata metadata;
    check(tee->generateKey(alias, std::nullopt, generation, 0, {}, &metadata), "generateKey");
    expect(metadata.key.domain == Domain::KEY_ID, "generated key is addressed by id");

    std::vector<KeyDescriptor> entries;
    check(keystore->listEntries(Domain::APP, -1, &entries), "listEntries");
    bool listed = false;
    for (const auto& entry : entries) listed |= entry.alias == alias.alias;
    expect(listed, "key listed under its alias");

    const std::vector<uint8_t> plain(48, 'k');
    auto run = [&](const std::vector<KeyParameter>& parameters, const std::vector<uint8_t>& input,
                   std::optional<KeyParameters>* returned) {
        CreateOperationResponse response;
        check(tee->createOperation(metadata.key, parameters, false, &response), "createOperation");
        if (returned != nullptr) *returned = response.parameters;
        std::optional<std::vector<uint8_t>> output;
        check(response.iOperation->finish(input, std::nullopt, &output), "finish");
        return output.value_or(std::vector<uint8_t>{});
    };
    std::optional<KeyParameters> encryption;
    auto cipher = run({parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::ENCRYPT),
                       parameter<KeyParameterValue::blockMode>(Tag::BLOCK_MODE, BlockMode::GCM),
                       parameter<KeyParameterValue::paddingMode>(Tag::PADDING, PaddingMode::NONE),
                       parameter<KeyParameterValue::integer>(Tag::MAC_LENGTH, 128)},
                      plain, &encryption);
    expect(cipher.size() == plain.size() + 16, "AES-GCM ciphertext with tag");
    std::vector<uint8_t> nonce;
    for (const auto& value : encryption.value_or(KeyParameters{}).keyParameter)
        if (value.tag == Tag::NONCE) nonce = value.value.get<KeyParameterValue::blob>();
    expect(!nonce.empty(), "KeyMint chose the nonce");
    auto decrypted = run({parameter<KeyParameterValue::keyPurpose>(Tag::PURPOSE, KeyPurpose::DECRYPT),
                          parameter<KeyParameterValue::blockMode>(Tag::BLOCK_MODE, BlockMode::GCM),
                          parameter<KeyParameterValue::paddingMode>(Tag::PADDING, PaddingMode::NONE),
                          parameter<KeyParameterValue::integer>(Tag::MAC_LENGTH, 128),
                          parameter<KeyParameterValue::blob>(Tag::NONCE, nonce)},
                         cipher, nullptr);
    expect(decrypted == plain, "AES-GCM round trip");

    check(keystore->deleteKey(alias), "deleteKey");
    KeyEntryResponse gone;
    expect(!keystore->getKeyEntry(alias, &gone).isOk(), "deleted key is gone");
    std::cout << "PASS keystore2 service\n";
    return 0;
}
