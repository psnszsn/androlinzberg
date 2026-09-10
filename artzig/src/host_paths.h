#pragma once

#ifdef __cplusplus
extern "C" {
#endif
const char* azk_host_path(const char* original);
void azk_host_path_release(const char* original, const char* mapped);
#ifdef __cplusplus
}

// Retain native resource ownership in the original caller; only its pathname
// argument is translated. The original string remains available for identity.
class HostedFilePath {
public:
    explicit HostedFilePath(const char* path) : original_(path), mapped_(azk_host_path(path)) {}
    ~HostedFilePath() { azk_host_path_release(original_, mapped_); }
    const char* c_str() const { return mapped_; }
    HostedFilePath(const HostedFilePath&) = delete;
    HostedFilePath& operator=(const HostedFilePath&) = delete;
private:
    const char* original_;
    const char* mapped_;
};
#endif
