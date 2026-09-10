// Pinned Zygote.cpp isolateAppData, DropCapabilitiesBoundingSet and
// SetCapabilities: a child zygote with no package allowlist sees empty CE/DE
// roots. The host uses session data-layout links instead of /data_mirror and
// SELinux labels. Namespace/mount/capability constructors have no std.Io API.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#include <linux/capability.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

// Returns errno, never continues after a partial setup failure. The caller must
// exit on failure. Call after creating the UID namespace, before ART/threads.
int azk_process_isolate_app_data(const char *root) {
    if (!root || root[0] != '/' || strcmp(root, "/") == 0) return EINVAL;
    DIR *tasks = opendir("/proc/self/task");
    if (!tasks) return errno;
    unsigned count = 0;
    struct dirent *entry;
    while ((entry = readdir(tasks))) if (entry->d_name[0] != '.') count++;
    closedir(tasks);
    if (count != 1) return EBUSY;

    char canonical[PATH_MAX], paths[2][PATH_MAX];
    if (!realpath(root, canonical)) return errno;
    if (strcmp(canonical, "/") == 0) return EINVAL;
    const char *names[] = {"user", "user_de"};
    for (unsigned i = 0; i < 2; i++) {
        int size = snprintf(paths[i], sizeof(paths[i]), "%s/%s", canonical, names[i]);
        if (size < 0 || (size_t)size >= sizeof(paths[i])) return ENAMETOOLONG;
        struct stat metadata;
        if (lstat(paths[i], &metadata)) return errno;
        // Session storage creates directories here. Reject redirected roots.
        if (!S_ISDIR(metadata.st_mode)) return ENOTDIR;
    }
    if (unshare(CLONE_NEWNS)) return errno;
    if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL)) return errno;
    char options[128];
    int size = snprintf(options, sizeof(options), "mode=0700,uid=%u,gid=%u,size=67108864",
        (unsigned)getuid(), (unsigned)getgid());
    if (size < 0 || (size_t)size >= sizeof(options)) return EOVERFLOW;
    for (unsigned i = 0; i < 2; i++) {
        if (mount("tmpfs", paths[i], "tmpfs", MS_NOSUID | MS_NODEV | MS_NOEXEC, options))
            return errno;
    }
    // Android's legacy /data/data link targets user/0 in this session layout,
    // so both that alias and canonical paths now see the same empty CE root.
    // Public APKs under app/ and the runtime cache retain their existing view.
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) return errno;
    for (int capability = 0; ; capability++) {
        if (prctl(PR_CAPBSET_READ, capability, 0, 0, 0) < 0) {
            if (errno != EINVAL) return errno;
            break;
        }
        if (prctl(PR_CAPBSET_DROP, capability, 0, 0, 0)) return errno;
    }
    if (prctl(PR_CAP_AMBIENT, PR_CAP_AMBIENT_CLEAR_ALL, 0, 0, 0)) return errno;
    struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3 };
    struct __user_cap_data_struct capabilities[2] = {{0}, {0}};
    if (syscall(SYS_capset, &header, capabilities)) return errno;
    return 0;
}
