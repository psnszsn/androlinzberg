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
void azk_parcel_destroyOwned(AParcel *);
void AParcel_markSensitive(const AParcel *);
bool AParcel_getAllowFds(const AParcel *);
int32_t AParcel_getDataSize(const AParcel *);
int32_t AParcel_reset(AParcel *);
int32_t AParcel_unmarshal(AParcel *, const uint8_t *, size_t);
int32_t azk_parcel_setSize(AParcel *, size_t);
int32_t azk_parcel_setCapacity(AParcel *, size_t);
size_t azk_parcel_capacity(const AParcel *);
size_t azk_parcel_objectsCount(const AParcel *);
int32_t azk_parcel_error(const AParcel *);
void azk_parcel_setError(AParcel *, int32_t);
size_t azk_parcel_allocSize(void);
size_t azk_parcel_allocCount(void);
void azk_parcel_freeData(AParcel *);
int32_t azk_parcel_hasObjectsInRange(const AParcel *, size_t, size_t, bool, bool *);
int azk_parcel_ownedFd(const AParcel *, size_t);
void azk_parcel_noteRequestHeader(const AParcel *);
bool azk_parcel_replaceWorkSource(AParcel *, uint32_t);
uint32_t azk_parcel_readWorkSource(const AParcel *);
const void *azk_parcel_data(const AParcel *);
void *azk_parcel_writeInplace(AParcel *, size_t);
const void *azk_parcel_readInplace(const AParcel *, size_t);
bool azk_parcel_allowFds(AParcel *, bool);
bool azk_parcel_hasFds(const AParcel *);
int32_t azk_parcel_writeFd(AParcel *, int, bool);
int32_t azk_parcel_dupFd(int);
void azk_parcel_closeFd(int);
int32_t azk_parcel_readFileDescriptorBorrowed(const AParcel *, int *);
int32_t azk_parcel_detachParcelFileDescriptor(int);
void *azk_parcel_cppView(AParcel *, void *(*)(AParcel *), void (*)(void *));
int32_t azk_binder_transact(AIBinder *, uint32_t, const AParcel *, AParcel *,
                            uint32_t);
uint32_t azk_binder_callFlags(void);
AIBinder *azk_binder_contextObject(void);
const void *azk_binder_servingStack(void);
uint64_t azk_binder_clearCallingIdentity(void);
void azk_binder_restoreCallingIdentity(uint64_t);
bool azk_binder_hasExplicitIdentity(void);
int32_t azk_binder_getStrictModePolicy(void);
void azk_binder_setStrictModePolicy(int32_t);
uint32_t azk_binder_getWorkSource(void);
uint64_t azk_binder_setWorkSource(uint32_t, bool);
void azk_binder_restoreWorkSource(uint64_t);
bool azk_binder_shouldPropagateWorkSource(void);
void azk_binder_flush(void);
void azk_binder_blockUntilThreadAvailable(void);
bool azk_property_write(const char *name, const char *value);
// Snapshot callback: value bytes are borrowed until the callback returns.
bool azk_property_read(const char *name,
                       void (*callback)(void *, const char *, size_t),
                       void *context);
#ifdef __cplusplus
}
#endif
