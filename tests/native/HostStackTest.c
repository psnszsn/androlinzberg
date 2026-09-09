#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <linux/filter.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <sched.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/syscall.h>

// The pinned libc owns this private API and its pthread layout. Tests compare
// only its results with public pthread APIs; no application code reads that layout.
typedef int (*StackQuery)(pthread_t, void **, size_t *, size_t *);
static StackQuery query;

static void *check_stack(void *label) {
    pthread_attr_t attrs;
    void *base = NULL;
    size_t size = 0, guard = 0;
    int local;
    if (pthread_getattr_np(pthread_self(), &attrs) ||
        pthread_attr_getstack(&attrs, &base, &size) ||
        pthread_attr_getguardsize(&attrs, &guard) || pthread_attr_destroy(&attrs))
        return (void *)1;
    if ((uintptr_t)&local < (uintptr_t)base ||
        (uintptr_t)&local >= (uintptr_t)base + size) return (void *)1;

    struct sock_filter instructions[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, SYS_sched_getaffinity, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | EPERM),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog filter = {
        .len = sizeof(instructions) / sizeof(instructions[0]), .filter = instructions,
    };
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) ||
        prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter)) return (void *)1;

    // Deny both pid=0 and explicit thread IDs, as Chromium does. The ordinary
    // getter must still report its failure, with no fabricated affinity result.
    cpu_set_t cpus;
    if (sched_getaffinity(0, sizeof(cpus), &cpus) != -1 || errno != EPERM ||
        pthread_getaffinity_np(pthread_self(), sizeof(cpus), &cpus) != EPERM ||
        pthread_getattr_np(pthread_self(), &attrs) != EPERM) return (void *)1;
    for (int i = 0; i < 32; ++i) {
        void *actual_base = NULL;
        size_t actual_size = 0, actual_guard = 0;
        if (query(pthread_self(), &actual_base, &actual_size, &actual_guard) ||
            actual_base != base || actual_size != size || actual_guard != guard)
            return (void *)1;
    }
    printf("PASS %s: exact stack size=%zu guard=%zu under affinity denial\n",
           (const char *)label, size, guard);
    return NULL;
}

static int run(const char *label, pthread_attr_t *attrs) {
    pthread_t thread;
    void *result = (void *)1;
    if (pthread_create(&thread, attrs, check_stack, (void *)label)) return 1;
    return pthread_join(thread, &result) || result != NULL;
}

int main(void) {
    query = (StackQuery)dlsym(RTLD_DEFAULT, "__artzig_pthread_getstack");
    if (!query) {
        fprintf(stderr, "pinned host stack query unavailable: %s\n", dlerror());
        return 1;
    }
    void *base = (void *)1;
    size_t size = 2, guard = 3;
    if (query(pthread_self(), &base, &size, &guard) != ENOTSUP ||
        base != (void *)1 || size != 2 || guard != 3) return 1;
    pthread_attr_t attrs;
    if (pthread_getattr_np(pthread_self(), &attrs) || pthread_attr_destroy(&attrs)) return 1;
    puts("PASS initial thread retains ordinary pre-sandbox attribute query");
    if (run("default allocation", NULL)) return 1;
    if (pthread_attr_init(&attrs) || pthread_attr_setstacksize(&attrs, 1024 * 1024) ||
        pthread_attr_setguardsize(&attrs, 3 * 4096)) return 1;
    if (run("explicit size and guard", &attrs)) return 1;
    void *allocation = mmap(NULL, 1024 * 1024, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (allocation == MAP_FAILED) return 1;
    if (pthread_attr_setstack(&attrs, allocation, 1024 * 1024)) return 1;
    int result = run("caller-owned allocation", &attrs);
    if (pthread_attr_destroy(&attrs) || munmap(allocation, 1024 * 1024)) return 1;
    return result;
}
