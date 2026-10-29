// QMI on QRTR has no QMUX header. Each datagram is
//   u8 type | u16 txn | u16 msg_id | u16 tlv_len | TLVs...
// Each connected socket is an independent QMI client, so the RIL is unaffected.

#include <jni.h>
#include <errno.h>
#include <limits.h>
#include <linux/qrtr.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#define QMI_HEADER 7
#define QMI_MAX 65536
#define QMI_RESPONSE 2
#define LOOKUP_TIMEOUT_MS 1500

// "qrtr:<step>:<errno>:<strerror>", parsed by Qrtr.reword in Qmi.kt.
static void throw_io(JNIEnv *env, const char *step, int err) {
    char msg[160];
    snprintf(msg, sizeof msg, "qrtr:%s:%d:%s", step, err, strerror(err));
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, msg);
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t)ts.tv_sec * 1000) + (ts.tv_nsec / 1000000);
}

static uint16_t le16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

// 1 when the socket has something to read, 0 once the deadline has passed, -errno when waiting fails.
static int wait_readable(int fd, int64_t deadline) {
    for (;;) {
        int64_t left = deadline - now_ms();
        if (left <= 0) return 0;
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int ready = poll(&p, 1, left > INT_MAX ? INT_MAX : (int)left);
        if (ready > 0) return 1;
        if (ready < 0 && errno != EINTR) return -errno;
    }
}

// instance is packed as (version | instance << 8), and 0 is a kernel-side wildcard.
static int lookup(uint32_t service, uint32_t instance, struct sockaddr_qrtr *server) {
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -errno;

    struct sockaddr_qrtr ctrl = {.sq_family = AF_QIPCRTR, .sq_node = 1, .sq_port = QRTR_PORT_CTRL};
    struct sockaddr_qrtr self;
    socklen_t self_len = sizeof self;
    if (getsockname(fd, (struct sockaddr *)&self, &self_len) == 0) ctrl.sq_node = self.sq_node;

    struct qrtr_ctrl_pkt req = {.cmd = QRTR_TYPE_NEW_LOOKUP, .server = {.service = service, .instance = instance}};
    int rc = -ENOENT;
    if (sendto(fd, &req, sizeof req, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        rc = -errno;
        close(fd);
        return rc;
    }

    int64_t deadline = now_ms() + LOOKUP_TIMEOUT_MS;
    for (;;) {
        int ready = wait_readable(fd, deadline);
        if (ready < 0) rc = ready;
        if (ready <= 0) break;
        struct qrtr_ctrl_pkt pkt;
        ssize_t n = recv(fd, &pkt, sizeof pkt, 0);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            rc = -errno;
            break;
        }
        if ((size_t)n < sizeof pkt || pkt.cmd != QRTR_TYPE_NEW_SERVER) continue;
        // An all-zero NEW_SERVER ends the lookup results.
        if (!pkt.server.service && !pkt.server.instance && !pkt.server.node && !pkt.server.port) break;
        if (pkt.server.service == service && pkt.server.port) {
            *server = (struct sockaddr_qrtr){.sq_family = AF_QIPCRTR, .sq_node = pkt.server.node, .sq_port = pkt.server.port};
            rc = 0;
            break;
        }
    }
    close(fd);
    return rc;
}

// Sends the request held in buf, then reads into buf until its response arrives. Returns the response's length,
// or -errno with *failed saying which step went wrong.
static ssize_t exchange(int fd, uint8_t *buf, size_t len, int timeout_ms, const char **failed) {
    uint16_t txn = le16(buf + 1);
    uint16_t msg = le16(buf + 3);

    ssize_t sent = send(fd, buf, len, 0);
    if (sent != (ssize_t)len) {
        *failed = "send";
        return sent < 0 ? -errno : -EIO;
    }

    int64_t deadline = now_ms() + timeout_ms;
    for (;;) {
        int ready = wait_readable(fd, deadline);
        if (ready == 0) {
            *failed = "timeout";
            return -ETIMEDOUT;
        }
        if (ready < 0) {
            *failed = "wait";
            return ready;
        }
        ssize_t n = recv(fd, buf, QMI_MAX, 0);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            *failed = "recv";
            return -errno;
        }
        // Indications and late responses to earlier requests arrive on the same socket.
        if (n >= QMI_HEADER && buf[0] == QMI_RESPONSE && le16(buf + 1) == txn && le16(buf + 3) == msg) return n;
    }
}

JNIEXPORT jint JNICALL
Java_dev_ririxi_bunnylock_root_Qrtr_open(JNIEnv *env, jclass clazz, jint service, jint timeoutMs) {
    (void)clazz;
    struct sockaddr_qrtr server;
    int rc = lookup((uint32_t)service, 1, &server);
    if (rc != 0) rc = lookup((uint32_t)service, 0, &server);
    if (rc != 0) {
        throw_io(env, "lookup", -rc);
        return -1;
    }

    int fd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        throw_io(env, "socket", errno);
        return -1;
    }
    struct timeval tv = {.tv_sec = timeoutMs / 1000, .tv_usec = (timeoutMs % 1000) * 1000L};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);

    if (connect(fd, (struct sockaddr *)&server, sizeof server) < 0) {
        int err = errno;
        close(fd);
        throw_io(env, "connect", err);
        return -1;
    }
    return fd;
}

JNIEXPORT jbyteArray JNICALL
Java_dev_ririxi_bunnylock_root_Qrtr_transact(JNIEnv *env, jclass clazz, jint fd, jbyteArray request, jint timeoutMs) {
    (void)clazz;
    jsize len = (*env)->GetArrayLength(env, request);
    if (len < QMI_HEADER || len >= QMI_MAX) {
        throw_io(env, "length", EINVAL);
        return NULL;
    }
    uint8_t buf[QMI_MAX];
    (*env)->GetByteArrayRegion(env, request, 0, len, (jbyte *)buf);

    const char *failed = NULL;
    ssize_t n = exchange(fd, buf, (size_t)len, timeoutMs, &failed);
    if (n < 0) {
        throw_io(env, failed, (int)-n);
        return NULL;
    }
    jbyteArray reply = (*env)->NewByteArray(env, (jsize)n);
    if (reply) (*env)->SetByteArrayRegion(env, reply, 0, (jsize)n, (const jbyte *)buf);
    return reply;
}

JNIEXPORT void JNICALL
Java_dev_ririxi_bunnylock_root_Qrtr_close(JNIEnv *env, jclass clazz, jint fd) {
    (void)env;
    (void)clazz;
    if (fd >= 0) close(fd);
}
