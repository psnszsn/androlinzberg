// artzig: minimal stand-ins for external/sqlite/android.
// LOCALIZED/UNICODE collators fall back to a byte-wise comparison, which is
// good enough for app-local databases without ICU collation tailoring.
#include "sqlite3_android.h"
#include <cstring>
#include <algorithm>

static int azk_collate_bytes(void*, int l1, const void* v1, int l2, const void* v2) {
    int n = std::min(l1, l2);
    int c = memcmp(v1, v2, n);
    if (c != 0) return c;
    return l1 - l2;
}

extern "C" int register_localized_collators(sqlite3* handle, const char*, int) {
    int err = sqlite3_create_collation(handle, "LOCALIZED", SQLITE_UTF8, nullptr, azk_collate_bytes);
    if (err != SQLITE_OK) return err;
    return sqlite3_create_collation(handle, "UNICODE", SQLITE_UTF8, nullptr, azk_collate_bytes);
}

extern "C" int register_android_functions(sqlite3* handle, int utf16Storage) {
    // PHONE_NUMBERS_EQUAL / _DELETE_FILE etc. are not needed; only collators.
    return register_localized_collators(handle, "en_US", utf16Storage);
}
