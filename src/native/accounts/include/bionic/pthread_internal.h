#pragma once
#include "private/grp_pwd.h"
// Original Bionic grp_pwd.cpp uses these buffers from its private pthread TLS.
// ART and glibc own different thread layouts; keep the original buffer types in
// host TLS, with independent passwd/group state and normal thread lifetime.
struct HostAccountTls { group_state_t group; passwd_state_t passwd; };
static HostAccountTls& __get_bionic_tls() {
    static thread_local HostAccountTls value{};
    return value;
}
