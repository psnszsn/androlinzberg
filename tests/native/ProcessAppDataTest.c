// Exercise the actual mount/capability boundary in disposable child namespaces.
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
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
#include <sys/wait.h>
#include <unistd.h>

extern int azk_process_isolate_app_data(const char *root);

static void write_file(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    assert(fd >= 0);
    size_t size = strlen(value);
    assert(write(fd, value, size) == (ssize_t)size);
    assert(close(fd) == 0);
}

static void enter_user(unsigned uid) {
    unsigned host_uid = getuid(), host_gid = getgid();
    assert(unshare(CLONE_NEWUSER) == 0);
    write_file("/proc/self/setgroups", "deny\n");
    char mapping[80];
    assert(snprintf(mapping, sizeof(mapping), "%u %u 1\n", uid, host_uid) > 0);
    write_file("/proc/self/uid_map", mapping);
    assert(snprintf(mapping, sizeof(mapping), "%u %u 1\n", uid, host_gid) > 0);
    write_file("/proc/self/gid_map", mapping);
    assert(getuid() == uid && getgid() == uid);
}

static void absent(const char *path) {
    assert(access(path, F_OK) == -1);
    assert(errno == ENOENT || errno == EACCES);
}

int main(void) {
    alarm(15);
    assert(azk_process_isolate_app_data(NULL) == EINVAL);
    assert(azk_process_isolate_app_data("/") == EINVAL);
    assert(azk_process_isolate_app_data("relative") == EINVAL);
    char temporary[] = "/tmp/azk-process-app-data-XXXXXX";
    assert(mkdtemp(temporary));
    char ce[PATH_MAX], de[PATH_MAX], apk[PATH_MAX], secret[PATH_MAX];
    char de_secret[PATH_MAX], alias[PATH_MAX], ephemeral[PATH_MAX];
    assert(snprintf(ce, sizeof(ce), "%s/user", temporary) > 0);
    assert(snprintf(de, sizeof(de), "%s/user_de", temporary) > 0);
    assert(snprintf(apk, sizeof(apk), "%s/provider.apk", temporary) > 0);
    assert(snprintf(secret, sizeof(secret), "%s/user/private", temporary) > 0);
    assert(snprintf(de_secret, sizeof(de_secret), "%s/user_de/private", temporary) > 0);
    assert(snprintf(alias, sizeof(alias), "%s/legacy-private", temporary) > 0);
    assert(snprintf(ephemeral, sizeof(ephemeral), "%s/user/ephemeral", temporary) > 0);
    assert(mkdir(ce, 0700) == 0 && mkdir(de, 0700) == 0);
    write_file(apk, "public provider code");
    write_file(secret, "private CE bytes");
    write_file(de_secret, "private DE bytes");
    assert(symlink(secret, alias) == 0);

    pid_t parent = getpid();
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        enter_user(1053);
        int error = azk_process_isolate_app_data(temporary);
        if (error) fprintf(stderr, "app data isolation: %s\n", strerror(error));
        assert(error == 0);
        absent(secret); absent(de_secret); absent(alias);
        assert(access(apk, R_OK) == 0);
        write_file(ephemeral, "namespace-private bytes");
        assert(prctl(PR_GET_NO_NEW_PRIVS, 0, 0, 0, 0) == 1);
        struct __user_cap_header_struct header = { .version = _LINUX_CAPABILITY_VERSION_3 };
        struct __user_cap_data_struct caps[2];
        assert(syscall(SYS_capget, &header, caps) == 0);
        for (unsigned i = 0; i < 2; i++)
            assert(caps[i].effective == 0 && caps[i].permitted == 0 && caps[i].inheritable == 0);
        assert(umount2(ce, MNT_DETACH) == -1 && errno == EPERM);
        char parent_view[PATH_MAX + 80];
        assert(snprintf(parent_view, sizeof(parent_view), "/proc/%d/root%s", parent, secret) > 0);
        absent(parent_view);

        // A renderer can create its own user namespace, but cannot unmount the
        // inherited locked view to recover its parent's original application data.
        pid_t renderer = fork();
        assert(renderer >= 0);
        if (renderer == 0) {
            enter_user(99000);
            assert(unshare(CLONE_NEWNS) == 0);
            absent(secret); absent(de_secret);
            assert(access(apk, R_OK) == 0 && access(ephemeral, R_OK) == 0);
            assert(umount2(ce, MNT_DETACH) == -1);
            absent(secret);
            _exit(0);
        }
        int status;
        assert(waitpid(renderer, &status, 0) == renderer && WIFEXITED(status) && WEXITSTATUS(status) == 0);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(access(secret, R_OK) == 0 && access(de_secret, R_OK) == 0 && access(alias, R_OK) == 0);
    absent(ephemeral);
    assert(unlink(alias) == 0 && unlink(secret) == 0 && unlink(de_secret) == 0 && unlink(apk) == 0);
    assert(rmdir(ce) == 0 && rmdir(de) == 0 && rmdir(temporary) == 0);
    puts("Child Zygote data isolation: CE/DE hidden, APK visible, capabilities dropped, parent and descendant boundaries intact");
    return 0;
}
