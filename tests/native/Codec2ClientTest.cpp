// Linking this factory retains the complete pinned NDK MediaCodec/CCodec client.
// Service activation and decoding are separate from this client contract test.
#include <android/versioning.h>
#include <media/NdkMediaCodec.h>
#include <array>
#include <cassert>
#include <cstring>
#include <dlfcn.h>
#include <iostream>

auto volatile codecFactory = &AMediaCodec_createCodecByName;
extern "C" int android_get_application_target_sdk_version();

int main() {
    assert(codecFactory != nullptr);
    Dl_info factoryOwner{}, cryptoOwner{};
    assert(dladdr(reinterpret_cast<void *>(codecFactory), &factoryOwner));
    assert(dladdr(reinterpret_cast<void *>(&AMediaCodecCryptoInfo_new), &cryptoOwner));
    const char *libraryName = std::strrchr(factoryOwner.dli_fname, '/');
    assert(libraryName && std::strcmp(libraryName + 1, "libandroid_platform.so") == 0);
    assert(cryptoOwner.dli_fbase == factoryOwner.dli_fbase);
    assert(android_get_application_target_sdk_version() == 10000);
    std::array<uint8_t, 16> key{}, iv{};
    key.fill(0x35);
    iv.fill(0xa7);
    size_t clear[] = {3, 17};
    size_t encrypted[] = {64, 128};
    auto *info = AMediaCodecCryptoInfo_new(2, key.data(), iv.data(),
            AMEDIACODECRYPTOINFO_MODE_AES_CTR, clear, encrypted);
    assert(info);
    // The API owns a copy of all caller storage.
    key.fill(0);
    iv.fill(0);
    clear[0] = encrypted[0] = 0;
    assert(AMediaCodecCryptoInfo_getNumSubSamples(info) == 2);
    assert(AMediaCodecCryptoInfo_getMode(info) == AMEDIACODECRYPTOINFO_MODE_AES_CTR);
    assert(AMediaCodecCryptoInfo_getKey(info, key.data()) == AMEDIA_OK);
    assert(AMediaCodecCryptoInfo_getIV(info, iv.data()) == AMEDIA_OK);
    for (auto byte : key) assert(byte == 0x35);
    for (auto byte : iv) assert(byte == 0xa7);
    assert(AMediaCodecCryptoInfo_getClearBytes(info, clear) == AMEDIA_OK);
    assert(AMediaCodecCryptoInfo_getEncryptedBytes(info, encrypted) == AMEDIA_OK);
    assert(clear[0] == 3 && clear[1] == 17);
    assert(encrypted[0] == 64 && encrypted[1] == 128);
    assert(AMediaCodecCryptoInfo_getKey(nullptr, key.data()) == AMEDIA_ERROR_INVALID_OBJECT);
    assert(AMediaCodecCryptoInfo_getKey(info, nullptr) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AMediaCodecCryptoInfo_getIV(nullptr, iv.data()) == AMEDIA_ERROR_INVALID_OBJECT);
    assert(AMediaCodecCryptoInfo_getIV(info, nullptr) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AMediaCodecCryptoInfo_getClearBytes(nullptr, clear) == AMEDIA_ERROR_INVALID_OBJECT);
    assert(AMediaCodecCryptoInfo_getClearBytes(info, nullptr) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AMediaCodecCryptoInfo_getEncryptedBytes(nullptr, encrypted) == AMEDIA_ERROR_INVALID_OBJECT);
    assert(AMediaCodecCryptoInfo_getEncryptedBytes(info, nullptr) == AMEDIA_ERROR_INVALID_PARAMETER);
    assert(AMediaCodecCryptoInfo_delete(info) == AMEDIA_OK);
    std::cout << "PASS stock native codec client link, NDK crypto metadata ownership and invalid arguments" << std::endl;
}
