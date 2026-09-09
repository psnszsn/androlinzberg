#pragma once
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <climits>
#include <sys/prctl.h>

// The headless runner owns these processes. Opt in only its PID and descendants
// (including its GDB child), so Yama permits bounded failure-stack capture.
inline bool allowTestStackCapture() {
    const char* value = getenv("AZK_TEST_PTRACER_PID");
    if (value == nullptr) return true;
    char* end = nullptr;
    errno = 0;
    const long tracer = strtol(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || tracer <= 0 || tracer > INT_MAX) {
        fprintf(stderr, "invalid AZK_TEST_PTRACER_PID\n");
        return false;
    }
    if (prctl(PR_SET_PTRACER, static_cast<unsigned long>(tracer), 0UL, 0UL, 0UL) != 0) {
        fprintf(stderr, "cannot authorize test stack capture: %s\n", strerror(errno));
        return false;
    }
    return true;
}

