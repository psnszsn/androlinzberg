#pragma once
#include <assert.h>
// Internal language boundary. Android operations and descriptor ownership are
// implemented once in Zig. This is not an addition to Android's stable NDK ABI.
#pragma push_macro("__assert")
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#pragma pop_macro("__assert")
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
AParcel *AParcel_create(void);
void AParcel_markSensitive(const AParcel *);
bool AParcel_getAllowFds(const AParcel *);
int32_t AParcel_getDataSize(const AParcel *);
int32_t AParcel_reset(AParcel *);
int32_t azk_parcel_setSize(AParcel *, size_t);
int32_t azk_parcel_setCapacity(AParcel *, size_t);
size_t azk_parcel_capacity(const AParcel *);
int32_t azk_parcel_error(const AParcel *);
void azk_parcel_setError(AParcel *, int32_t);
const void *azk_parcel_data(const AParcel *);
void *azk_parcel_writeInplace(AParcel *, size_t);
const void *azk_parcel_readInplace(const AParcel *, size_t);
bool azk_parcel_allowFds(AParcel *, bool);
bool azk_parcel_hasFds(const AParcel *);
int32_t azk_parcel_writeFd(AParcel *, int, bool);
int32_t azk_parcel_dupFd(int);
void azk_parcel_closeFd(int);
int32_t azk_parcel_readFileDescriptorBorrowed(const AParcel *, int *);
void *azk_parcel_cppView(AParcel *, void *(*)(AParcel *), void (*)(void *));
int32_t azk_binder_transact(AIBinder *, uint32_t, const AParcel *, AParcel *,
                            uint32_t);
uint32_t azk_binder_callFlags(void);
const void *azk_binder_servingStack(void);
uint64_t azk_binder_clearCallingIdentity(void);
void azk_binder_restoreCallingIdentity(uint64_t);
void azk_binder_flush(void);
#ifdef __cplusplus
}
#endif
