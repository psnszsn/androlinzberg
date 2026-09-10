#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
typedef void (*azk_process_credentials_mapper)(int32_t, uint32_t*, uint32_t*);
int azk_process_identity_initialize(uint32_t uid, uint32_t gid, azk_process_credentials_mapper mapper);
void azk_process_map_credentials(int32_t pid, uint32_t* uid, uint32_t* gid);
int azk_process_check_uid_change(uint32_t real, uint32_t effective, int allow_unchanged);
int azk_process_check_gid_change(uint32_t real, uint32_t effective, int allow_unchanged);
uint32_t azk_process_getuid(void);
uint32_t azk_process_geteuid(void);
uint32_t azk_process_getgid(void);
uint32_t azk_process_getegid(void);
#ifdef __cplusplus
}
#endif
