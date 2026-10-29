// Network Helper addition: inspect actual QRTR/QMUX descriptors, never QMI payloads.
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/qrtr.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#ifndef SYS_pidfd_open
#define SYS_pidfd_open 434
#endif
#ifndef SYS_pidfd_getfd
#define SYS_pidfd_getfd 438
#endif
#ifndef SYS_pidfd_send_signal
#define SYS_pidfd_send_signal 424
#endif

static unsigned long long birth(int pid) {
    char path[64], buf[4096];
    snprintf(path, sizeof path, "/proc/%d/stat", pid);
    FILE *f = fopen(path, "re");
    if (!f) return 0;
    size_t n = fread(buf, 1, sizeof(buf) - 1, f); fclose(f); buf[n] = 0;
    char *p = strrchr(buf, ')');
    if (!p || p[1] != ' ') return 0;
    p += 2;
    for (int field = 3; field < 22; ++field) {
        p = strchr(p, ' '); if (!p) return 0; ++p;
    }
    return strtoull(p, NULL, 10);
}

static int descriptor_link(int pid, int fd, char *out, size_t cap) {
    char path[96]; snprintf(path, sizeof path, "/proc/%d/fd/%d", pid, fd);
    ssize_t n = readlink(path, out, cap - 1);
    if (n < 0) return -errno;
    out[n] = 0; return 0;
}

static int is_qmux(const char *p) {
    return !strncmp(p, "/dev/cdc-wdm", 12) || !strncmp(p, "/dev/qcqmi", 10) ||
           !strncmp(p, "/dev/smdcntl", 12);
}

// 1: positively identified, 0: unrelated, negative errno: cannot inspect.
static int is_qmi_fd(int pid, int pidfd, int fd) {
    char link[512];
    if (descriptor_link(pid, fd, link, sizeof link)) return 0;
    if (is_qmux(link)) return 1;
    if (strncmp(link, "socket:[", 8)) return 0;
    int duplicate = (int)syscall(SYS_pidfd_getfd, pidfd, fd, 0);
    if (duplicate < 0) return -errno;
    struct sockaddr_storage addr; socklen_t len = sizeof addr;
    int result = getsockname(duplicate, (struct sockaddr *)&addr, &len) == 0 && addr.ss_family == AF_QIPCRTR;
    close(duplicate); // Do not keep another reference to a modem client's socket.
    return result;
}

static long long monotonic_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

JNIEXPORT jstring JNICALL Java_dev_ririxi_bunnylock_root_BackendCleanup_scan(JNIEnv *env, jclass cls) {
    (void)cls;
    char output[32768] = {0}; size_t used = 0;
    int denied = 0, unsupported = 0, truncated = 0, checked = 0;
    long long deadline = monotonic_ms() + 5000;
    DIR *proc = opendir("/proc");
    if (!proc) return (*env)->NewStringUTF(env, "#denied\t1\n");
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        if (monotonic_ms() > deadline || used > sizeof(output) - 512) { truncated = 1; break; }
        char *end; long raw = strtol(entry->d_name, &end, 10);
        if (*end || raw <= 1 || raw == getpid()) continue;
        int pid = (int)raw;
        char path[80]; struct stat st;
        snprintf(path, sizeof path, "/proc/%d", pid);
        if (stat(path, &st) || (st.st_uid != 0 && st.st_uid % 100000 < 10000)) continue;
        if (st.st_uid == 0) {
            char exe[512]; snprintf(path, sizeof path, "/proc/%d/exe", pid);
            ssize_t n = readlink(path, exe, sizeof(exe) - 1);
            if (n <= 0) continue;
            exe[n] = 0;
            if (strncmp(exe, "/data/", 6) && strncmp(exe, "/system/bin/app_process", sizeof("/system/bin/app_process") - 1)) continue;
        }
        unsigned long long start = birth(pid); if (!start) continue;
        int pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
        if (pidfd < 0) { if (errno == ENOSYS) { unsupported = 1; break; } continue; }
        snprintf(path, sizeof path, "/proc/%d/fd", pid);
        DIR *fds = opendir(path);
        if (!fds) { ++denied; close(pidfd); continue; }
        struct dirent *de; int pid_denied = 0;
        while ((de = readdir(fds)) != NULL) {
            int fd = (int)strtol(de->d_name, &end, 10); if (*end || fd < 0) continue;
            int result = is_qmi_fd(pid, pidfd, fd);
            if (result < 0) {
                if (-result == ENOSYS) unsupported = 1;
                if (-result == EPERM || -result == EACCES) pid_denied = 1;
            } else ++checked;
            if (result == 1) {
                used += (size_t)snprintf(output + used, sizeof(output) - used, "%d\t%d\t%llu\t%u\n", pid, fd, start, st.st_uid);
                break; // One verified descriptor is sufficient; release revalidates it.
            }
            if (monotonic_ms() > deadline) { truncated = 1; break; }
        }
        denied += pid_denied; closedir(fds); close(pidfd);
    }
    closedir(proc);
    snprintf(output + used, sizeof(output) - used, "#denied\t%d\n#unsupported\t%d\n#truncated\t%d\n#checked\t%d\n", denied, unsupported, truncated, checked);
    return (*env)->NewStringUTF(env, output);
}

JNIEXPORT jint JNICALL Java_dev_ririxi_bunnylock_root_BackendCleanup_release(JNIEnv *env, jclass cls, jint pid, jint fd, jlong expected, jboolean terminate) {
    (void)env; (void)cls;
    if (pid <= 1 || pid == getpid() || expected <= 0) return EINVAL;
    int pidfd = (int)syscall(SYS_pidfd_open, pid, 0);
    if (pidfd < 0) return errno == ESRCH ? 0 : errno;
    if (birth(pid) != (unsigned long long)expected) { close(pidfd); return ESTALE; }
    int verified = is_qmi_fd(pid, pidfd, fd);
    if (verified != 1) { close(pidfd); return verified < 0 ? -verified : ESTALE; }
    if (!terminate) { close(pidfd); return 0; }
    int result = 0;
    if (syscall(SYS_pidfd_send_signal, pidfd, SIGTERM, NULL, 0) < 0 && errno != ESRCH) result = errno;
    struct pollfd wait = {.fd = pidfd, .events = POLLIN};
    if (!result && poll(&wait, 1, 350) == 0) {
        if (syscall(SYS_pidfd_send_signal, pidfd, SIGKILL, NULL, 0) < 0 && errno != ESRCH) result = errno;
        if (!result && poll(&wait, 1, 650) == 0) result = ETIMEDOUT;
    }
    close(pidfd); return result;
}
