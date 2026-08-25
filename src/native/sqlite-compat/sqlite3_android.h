// artzig: stand-in for external/sqlite/android/sqlite3_android.h.
// Android-specific SQL functions (PHONE_NUMBERS_EQUAL, localized collators)
// are not needed by app-local Room databases; register no-ops.
#pragma once
#include <sqlite3.h>
#define UTF16_STORAGE 0
extern "C" {
int register_android_functions(sqlite3* handle, int utf16Storage);
int register_localized_collators(sqlite3* handle, const char* systemLocale, int utf16Storage);
}
