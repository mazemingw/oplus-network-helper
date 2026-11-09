#define _GNU_SOURCE

/* diag.c -- DIAG transport: QRTR, with /dev/diag for external modems.
 *
 * The DIAG packet layer follows qfenix (BSD-3-Clause) and libopenpst; the
 * QRTR framing is what an SM8350 and an SM8850 modem actually put on the bus.
 * The /dev/diag container format follows what QCSuper and MobileInsight
 * document publicly.
 */
#include "diag.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

const char *diag_error(const diag_t *d) { return d->last_error; }

static void seterr(diag_t *d, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(d->last_error, sizeof d->last_error, fmt, ap);
    va_end(ap);
}

static char g_mock[512];
static int  g_mock_framing;          /* 0 = QRTR wrapper, 1 = chardev, 2 = chardev + MDM */

static int64_t now_ms(void);

void diag_set_mock(const char *path) { snprintf(g_mock, sizeof g_mock, "%s", path); }

void diag_set_mock_framing(const char *name)
{
    if (!strcmp(name, "chardev"))  g_mock_framing = 1;
    else if (!strcmp(name, "mdm")) g_mock_framing = 2;
    else                           g_mock_framing = 0;
}

int diag_is_mock(const diag_t *d) { return d->is_mock; }

const char *diag_transport_desc(const diag_t *d) { return d->desc[0] ? d->desc : "qrtr"; }

/* ---- QRTR ------------------------------------------------------------- */

#ifndef AF_QIPCRTR
#define AF_QIPCRTR            42
#endif
#define QRTR_PORT_CTRL        0xfffffffeu
#define QRTR_TYPE_NEW_SERVER  4
#define QRTR_TYPE_DEL_SERVER  5
#define QRTR_TYPE_NEW_LOOKUP  10

/* Which processor a DIAG endpoint belongs to.  The instance reads as
 * 64 * processor + channel, with processor 0 the modem and channel 1 the
 * command channel: the modem answers on instance 1 on SM8350 and SM8850,
 * and on an SM8250 the audio, sensor and compute DSPs publish 65, 257 and
 * 385 while the modem -- external there -- publishes none.  This is read
 * off those numbers rather than taken from documentation. */
#define DIAG_INSTANCE_PROC(inst)  ((inst) / 64)

struct sockaddr_qrtr {
    unsigned short sq_family;
    uint32_t sq_node;
    uint32_t sq_port;
};

struct qrtr_ctrl_pkt {
    uint32_t cmd;
    uint32_t service;
    uint32_t instance;
    uint32_t node;
    uint32_t port;
} __attribute__((packed));

static uint32_t g_qrtr_node, g_qrtr_port;
static int      g_qrtr_pinned;

void diag_set_qrtr(uint32_t node, uint32_t port)
{
    g_qrtr_node = node;
    g_qrtr_port = port;
    g_qrtr_pinned = 1;
}

/* ---- watching the modem come and go ------------------------------------ */

static uint32_t g_watch_me;

/* Subscribes a socket of its own to DIAG service announcements, so a caller
 * about to restart the modem can be told when it actually went away and when
 * it is back, instead of sleeping for a guessed length of time.  Must be
 * opened BEFORE the restart request goes out, or the announcements are missed.
 * Returns the socket, or -1 when there is no bus to watch (the mock, a kernel
 * without QRTR) and the caller should fall back to waiting. */
int diag_watch_open(void)
{
    if (g_mock[0]) return -1;

    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) return -1;

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) { close(sock); return -1; }
    g_watch_me = me.sq_node;

    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.service = QRTR_DIAG_SERVICE;
    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me.sq_node, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        close(sock);
        return -1;
    }

    /* The lookup first replays what is on the bus right now, ending with an
     * all-zero entry.  Drain that, so what arrives later is only live news.
     * Leftovers would be harmless anyway: a NEW_SERVER is only acted on after
     * a DEL_SERVER has been seen. */
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1000) <= 0) break;
        struct qrtr_ctrl_pkt in;
        if (recv(sock, &in, sizeof in, 0) < (ssize_t)sizeof in) continue;
        if (in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;
    }
    return sock;
}

/* Waits for the modem's DIAG service to register again, which is what says
 * the modem is back.  Its removal is only logged, never required: measured on
 * an SM8350, some restarts announce it and some do not, so waiting for the
 * removal first would miss the re-registration that follows a quiet one.
 *
 * Returns 0 once DIAG is back, 1 when the bus said nothing at all within
 * quiet_ms (the caller has then already waited that long and should just try
 * the modem), and -1 when the removal was announced but nothing came back
 * within back_ms of it.
 *
 * Anything the lookup replayed when the watch was opened has been drained
 * down to its end marker, so a registration seen here is a genuinely new one
 * and cannot be the service that was there before the restart. */
int diag_watch_cycle(int fd, int quiet_ms, int back_ms)
{
    int gone = 0;
    int64_t deadline = now_ms() + quiet_ms;

    for (;;) {
        int left = (int)(deadline - now_ms());
        if (left <= 0) return gone ? -1 : 1;

        struct pollfd p = { fd, POLLIN, 0 };
        int pr = poll(&p, 1, left);
        if (pr < 0) {
            if (errno == EINTR) continue;
            return gone ? -1 : 1;
        }
        if (pr == 0) continue;            /* deadline is re-checked at the top */

        struct qrtr_ctrl_pkt in;
        if (recv(fd, &in, sizeof in, 0) < (ssize_t)sizeof in) continue;
        if (in.service != QRTR_DIAG_SERVICE) continue;
        if (in.node == g_watch_me) continue;     /* our own side, not the modem */

        if (in.cmd == QRTR_TYPE_DEL_SERVER && !gone) {
            gone = 1;
            qlog("modem restart: DIAG left the bus (node %u)", in.node);
            /* Now that the modem is known to be down, allow it the longer
             * budget to come back rather than only the quiet window. */
            int64_t back = now_ms() + back_ms;
            if (back > deadline) deadline = back;
        } else if (in.cmd == QRTR_TYPE_NEW_SERVER) {
            qlog("modem restart: DIAG is back on the bus (node %u port %u)", in.node, in.port);
            return 0;
        }
    }
}

void diag_watch_close(int fd) { if (fd >= 0) close(fd); }

/* ---- the bus scan, for the verbose log --------------------------------- */

/* A QRTR service id is the QMI service id, so the well-known ones have
 * names.  Only the ones that are certain are listed; the rest print as
 * bare numbers rather than as a guess. */
static const struct { uint32_t id; const char *name; } k_services[] = {
    {  1, "WDS" },   {  2, "DMS" },   {  3, "NAS" },   {  4, "QOS" },
    {  5, "WMS" },   {  9, "VOICE" }, { 11, "UIM" },   { 12, "PBM" },
    { 14, "RMTFS" }, { 16, "LOC" },   { 17, "SAR" },   { 26, "WDA" },
    { 36, "PDC" },   { 42, "DSD" },   { 43, "SSCTL" },
    { 64, "SERVREG_LOC" }, { 66, "SERVREG_NOTIF" }, { 69, "WLFW" },
    { 4096, "TFTP" }, { QRTR_DIAG_SERVICE, "DIAG" },
};

static const char *service_name(uint32_t id)
{
    for (size_t i = 0; i < sizeof k_services / sizeof k_services[0]; i++)
        if (k_services[i].id == id) return k_services[i].name;
    return NULL;
}

struct qrtr_svc { uint32_t node, service, instance, port; };

static int cmp_svc(const void *a, const void *b)
{
    const struct qrtr_svc *x = a, *y = b;
    if (x->node != y->node) return x->node < y->node ? -1 : 1;
    if (x->service != y->service) return x->service < y->service ? -1 : 1;
    if (x->instance != y->instance) return x->instance < y->instance ? -1 : 1;
    return 0;
}

#define SCAN_MAX 512

/* Lists everything on the QRTR bus into the log, one line per node, and ends
 * with a verdict on DIAG.  When a custom kernel or firmware leaves the helper
 * with nothing to talk to, this is what shows it -- "the modem is up but does
 * not publish DIAG" and "no remote processor is on the bus at all" read very
 * differently from a bug in the app.
 *
 * It uses a socket of its own: a lookup subscribes the socket to every later
 * announcement, and those must never land in the DIAG receive path. */
static void qrtr_scan_bus(void)
{
    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) return;              /* open_qrtr reports this itself */

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) { close(sock); return; }

    /* Service 0 is the wildcard: the name service answers with every server
     * it knows, then an all-zero entry to mark the end. */
    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me.sq_node, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        qlog("QRTR bus scan: the lookup could not be sent: %s", strerror(errno));
        close(sock);
        return;
    }

    static struct qrtr_svc svc[SCAN_MAX];
    size_t n = 0, dropped = 0;
    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;
        struct qrtr_ctrl_pkt in;
        ssize_t r = recv(sock, &in, sizeof in, 0);
        if (r < (ssize_t)sizeof in || in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;   /* end */
        if (n < SCAN_MAX) svc[n++] = (struct qrtr_svc){ in.node, in.service, in.instance, in.port };
        else dropped++;
    }
    close(sock);

    if (n == 0) {
        qlog("QRTR bus scan: the bus is empty -- no processor has registered a "
             "single service, so there is nothing for the helper to reach");
        return;
    }
    qsort(svc, n, sizeof svc[0], cmp_svc);

    size_t nodes = 0;
    qlog("QRTR bus scan (this CPU is node %u):", me.sq_node);
    for (size_t i = 0; i < n; ) {
        uint32_t node = svc[i].node;
        size_t j = i, count = 0;
        char line[1024];
        int len = 0;
        while (j < n && svc[j].node == node) {
            /* One entry per service id, with a count when it is published
             * more than once (per SIM, per instance). */
            size_t k = j;
            while (k < n && svc[k].node == node && svc[k].service == svc[j].service) k++;
            const char *name = service_name(svc[j].service);
            if (len < (int)sizeof line - 48) {
                len += snprintf(line + len, sizeof line - len, "%s%u%s%s", count ? ", " : "",
                                svc[j].service, name ? " " : "", name ? name : "");
                if (k - j > 1) len += snprintf(line + len, sizeof line - len, " x%zu", k - j);
            }
            count++;
            j = k;
        }
        qlog("  node %u%s: %zu service%s - %s", node, node == me.sq_node ? " (this CPU)" : "",
             count, count == 1 ? "" : "s", line);
        nodes++;
        i = j;
    }
    if (dropped) qlog("  ... and %zu more not shown", dropped);

    /* The verdict.  The modem is another node publishing DIAG as processor 0;
     * DIAG from a DSP does not count (see DIAG_INSTANCE_PROC). */
    int remote = 0, dsp = 0, local = 0;
    for (size_t i = 0; i < n; i++) {
        if (svc[i].service != QRTR_DIAG_SERVICE) continue;
        if (svc[i].node == me.sq_node) local++;
        else if (DIAG_INSTANCE_PROC(svc[i].instance) == 0) remote++;
        else dsp++;
    }
    if (remote)
        qlog("QRTR bus scan: %zu services on %zu nodes; the modem publishes DIAG (%u), so "
             "the helper has a modem to talk to", n, nodes, QRTR_DIAG_SERVICE);
    else if (dsp)
        qlog("QRTR bus scan: %zu services on %zu nodes; DIAG (%u) is only published by DSPs, "
             "not by the modem -- an external modem is reached through /dev/diag instead",
             n, nodes, QRTR_DIAG_SERVICE);
    else if (local)
        qlog("QRTR bus scan: %zu services on %zu nodes; DIAG (%u) is only published by this "
             "CPU, not by the modem -- nothing for the helper to talk to", n, nodes, QRTR_DIAG_SERVICE);
    else
        qlog("QRTR bus scan: %zu services on %zu nodes, but no DIAG (%u) anywhere -- this "
             "firmware/kernel does not expose the modem's DIAG over QRTR, so there is nothing "
             "for the helper to talk to", n, nodes, QRTR_DIAG_SERVICE);
}

/* Enumerates the QRTR bus and picks the modem's DIAG command endpoint.  Only
 * an endpoint of processor 0 qualifies: a DSP speaks DIAG too, but has no
 * EFS behind it, and talking to one is what used to make an SM8250 fail with
 * "bad command" instead of saying the modem is simply not on the bus. */
static int qrtr_find_diag(diag_t *d, int sock, uint32_t me, uint32_t *node, uint32_t *port)
{
    struct qrtr_ctrl_pkt pkt;
    memset(&pkt, 0, sizeof pkt);
    pkt.cmd = QRTR_TYPE_NEW_LOOKUP;
    pkt.service = QRTR_DIAG_SERVICE;

    struct sockaddr_qrtr ctrl = { AF_QIPCRTR, me, QRTR_PORT_CTRL };
    if (sendto(sock, &pkt, sizeof pkt, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        seterr(d, "QRTR lookup failed: %s", strerror(errno));
        return -1;
    }

    int found = 0;
    uint32_t best_node = 0, best_port = 0, best_instance = 0;
    char others[160] = "";
    size_t olen = 0;

    for (;;) {
        struct pollfd p = { sock, POLLIN, 0 };
        if (poll(&p, 1, 1500) <= 0) break;

        struct qrtr_ctrl_pkt in;
        ssize_t n = recv(sock, &in, sizeof in, 0);
        if (n < (ssize_t)sizeof in) continue;
        if (in.cmd != QRTR_TYPE_NEW_SERVER) continue;
        if (!in.service && !in.instance && !in.node && !in.port) break;   /* end */
        if (in.service != QRTR_DIAG_SERVICE) continue;
        if (in.node == me) continue;               /* our own side, not the modem */

        qlog("QRTR diag service: node %u port %u instance %u", in.node, in.port, in.instance);
        if (DIAG_INSTANCE_PROC(in.instance) != 0) {
            qlog("  not the modem (processor %u), skipped", DIAG_INSTANCE_PROC(in.instance));
            if (olen < sizeof others - 32)
                olen += (size_t)snprintf(others + olen, sizeof others - olen, "%snode %u instance %u",
                                         olen ? ", " : "", in.node, in.instance);
            continue;
        }
        if (!found || (best_instance != 1 && in.instance == 1)) {
            best_node = in.node;
            best_port = in.port;
            best_instance = in.instance;
        }
        found = 1;
    }

    if (!found) {
        if (others[0])
            seterr(d, "the modem does not publish DIAG on the QRTR bus (DIAG there belongs "
                      "to other processors: %s)", others);
        else
            seterr(d, "no DIAG service (%d) published on the QRTR bus", QRTR_DIAG_SERVICE);
        return -1;
    }
    *node = best_node;
    *port = best_port;
    return 0;
}

static int open_qrtr(diag_t *d)
{
    int sock = socket(AF_QIPCRTR, SOCK_DGRAM, 0);
    if (sock < 0) {
        int e = errno;
        if (e == EAFNOSUPPORT)
            seterr(d, "this kernel has no QRTR support (socket(AF_QIPCRTR): %s), "
                      "so there is no bus to reach the modem through", strerror(e));
        else
            seterr(d, "socket(AF_QIPCRTR) failed: %s", strerror(e));
        return -1;
    }

    /* Once per run: every reconnect after a modem restart would repeat it. */
    static int scanned;
    if (g_verbose && !scanned) {
        scanned = 1;
        qrtr_scan_bus();
    }

    struct sockaddr_qrtr me;
    socklen_t l = sizeof me;
    memset(&me, 0, sizeof me);
    if (getsockname(sock, (struct sockaddr *)&me, &l) < 0) {
        seterr(d, "QRTR getsockname failed: %s", strerror(errno));
        close(sock);
        return -1;
    }

    uint32_t node = g_qrtr_node, port = g_qrtr_port;
    if (!g_qrtr_pinned && qrtr_find_diag(d, sock, me.sq_node, &node, &port) < 0) {
        close(sock);
        return -1;
    }

    d->qrtr_node = node;
    d->qrtr_port = port;
    d->transport = DIAG_TP_QRTR;
    snprintf(d->desc, sizeof d->desc, "qrtr %u:%u", node, port);
    qlog("using QRTR node %u port %u as the DIAG transport", node, port);
    return sock;
}

/* ---- /dev/diag, for a modem that is not on the bus -------------------- */

/* Snapdragon 865/870 have no modem of their own: the X55 sits on PCIe, and
 * its DIAG reaches the phone through the kernel's diagchar driver as a
 * "remote" processor, never through QRTR.  Measured on a Poco F3: the modem's
 * QMI services are on the bus, its DIAG is not, and /dev/diag exists.  So
 * when the bus has no modem DIAG, the helper opens the character device and
 * asks the driver to route to the external modem.
 *
 * Container format, both directions: a 32-bit data type first.  A write is
 * [type][token, external modem only][one HDLC frame].  A read is
 * [type][count] followed by count entries, each [len][data] -- or, for data
 * from an external modem, [token][len][data] with a negative token. */

#define DIAG_DEV_PATH             "/dev/diag"
#define USER_SPACE_DATA_TYPE      0x00000020
#define DIAG_IOCTL_SWITCH_LOGGING 7
#define DIAG_IOCTL_REMOTE_DEV     32
#define DIAG_IOCTL_QUERY_CON_ALL  40
#define MEMORY_DEVICE_MODE        2
#define DIAG_MD_LOCAL_BIT         0x1
#define DIAG_MD_MDM_BIT           0x2
#define DIAG_TOKEN_MDM            (-1)    /* addresses the first external modem */
#define DIAG_CON_DEFAULT          0x1F

/* The logging-mode argument has grown over the years; the newest layout is
 * tried first, and an older kernel reads only the prefix it knows. */
struct diag_mode_24 {
    uint32_t req_mode, peripheral_mask, pd_mask;
    uint8_t  mode_param, diag_id, pd_val, reserved;
    int32_t  peripheral, device_mask;
} __attribute__((packed));

struct diag_mode_20 {
    uint32_t req_mode, peripheral_mask, pd_mask;
    uint8_t  mode_param, diag_id, pd_val, reserved;
    int32_t  peripheral;
} __attribute__((packed));

struct diag_con_all { uint32_t diag_con_all, num_peripherals, upd_map_supported; };

/* CRC-16/X.25, the checksum every HDLC frame ends with. */
static uint16_t crc16_x25(const uint8_t *p, size_t n)
{
    uint16_t crc = 0xFFFF;
    while (n--) {
        crc ^= *p++;
        for (int i = 0; i < 8; i++)
            crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0x8408) : (uint16_t)(crc >> 1);
    }
    return (uint16_t)~crc;
}

static int hdlc_encode(const uint8_t *in, size_t len, uint8_t *out, size_t outsz)
{
    uint16_t crc = crc16_x25(in, len);
    uint8_t tail[2] = { (uint8_t)(crc & 0xFF), (uint8_t)(crc >> 8) };
    size_t o = 0;
    for (size_t i = 0; i < len + 2; i++) {
        uint8_t b = i < len ? in[i] : tail[i - len];
        if (b == 0x7E || b == 0x7D) {
            if (o + 2 > outsz) return -1;
            out[o++] = 0x7D;
            out[o++] = (uint8_t)(b ^ 0x20);
        } else {
            if (o + 1 > outsz) return -1;
            out[o++] = b;
        }
    }
    if (o + 1 > outsz) return -1;
    out[o++] = 0x7E;
    return (int)o;
}

/* Decodes one frame starting at in, up to and including its 0x7E terminator,
 * and reports in *used how much of in it took.  Returns the payload length
 * (CRC removed), -1 for a malformed or oversized frame, -2 for a bad CRC. */
static int hdlc_decode(const uint8_t *in, size_t len, size_t *used, uint8_t *out, size_t outsz)
{
    size_t o = 0, i = 0;
    int esc = 0, overflow = 0;
    for (; i < len; i++) {
        uint8_t b = in[i];
        if (b == 0x7E) {
            if (o == 0 && !esc) continue;          /* a leading flag, not an end */
            i++;
            break;
        }
        if (esc) { b ^= 0x20; esc = 0; }
        else if (b == 0x7D) { esc = 1; continue; }
        if (o < outsz) out[o++] = b; else overflow = 1;
    }
    *used = i;
    if (overflow || o < 3) return -1;
    uint16_t want = (uint16_t)(out[o - 2] | (out[o - 1] << 8));
    if (crc16_x25(out, o - 2) != want) return -2;
    return (int)(o - 2);
}

static int chardev_switch_logging(diag_t *d, int fd)
{
    uint32_t periph = DIAG_CON_DEFAULT;
    struct diag_con_all ca;
    memset(&ca, 0, sizeof ca);
    if (ioctl(fd, DIAG_IOCTL_QUERY_CON_ALL, &ca) == 0 && ca.diag_con_all) periph = ca.diag_con_all;
    int32_t devmask = DIAG_MD_LOCAL_BIT | (d->remote ? DIAG_MD_MDM_BIT : 0);

    /* Memory-device mode is what hands the responses to this process rather
     * than to USB.  It belongs to this descriptor: closing it gives the
     * previous mode back. */
    int first_errno = 0;
    for (int mp = 1; mp >= 0; mp--) {
        struct diag_mode_24 m;
        memset(&m, 0, sizeof m);
        m.req_mode = MEMORY_DEVICE_MODE;
        m.peripheral_mask = periph;
        m.mode_param = (uint8_t)mp;
        m.device_mask = devmask;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &m) == 0) {
            qlog("%s: memory-device mode on (peripherals 0x%x, devices 0x%x)", DIAG_DEV_PATH,
                 periph, devmask);
            return 0;
        }
        if (!first_errno) first_errno = errno;
    }
    {
        struct diag_mode_20 m;
        memset(&m, 0, sizeof m);
        m.req_mode = MEMORY_DEVICE_MODE;
        m.peripheral_mask = periph;
        m.mode_param = 1;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &m) == 0) return 0;
    }
    {
        int mode = MEMORY_DEVICE_MODE;
        if (ioctl(fd, DIAG_IOCTL_SWITCH_LOGGING, &mode) == 0) return 0;
    }
    seterr(d, "could not switch %s to memory-device mode: %s", DIAG_DEV_PATH,
           strerror(first_errno));
    return -1;
}

static int open_chardev(diag_t *d)
{
    int fd = open(DIAG_DEV_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        int e = errno;
        if (e == ENOENT)
            seterr(d, "%s does not exist (this kernel has no diagchar driver)", DIAG_DEV_PATH);
        else
            seterr(d, "cannot open %s: %s", DIAG_DEV_PATH, strerror(e));
        return -1;
    }

    uint16_t mask = 0;
    if (ioctl(fd, DIAG_IOCTL_REMOTE_DEV, &mask) < 0) mask = 0;
    d->remote = (mask & 1) ? 1 : 0;
    qlog("%s: remote processors 0x%04x%s", DIAG_DEV_PATH, mask,
         d->remote ? " -- an external modem is attached" : "");

    if (chardev_switch_logging(d, fd) < 0) { close(fd); return -1; }

    d->transport = DIAG_TP_CHARDEV;
    snprintf(d->desc, sizeof d->desc, "%s%s", DIAG_DEV_PATH, d->remote ? " (external modem)" : "");
    qlog("using %s as the DIAG transport", d->desc);
    return fd;
}

/* ---- the test rig ----------------------------------------------------- */

static int open_mock(diag_t *d)
{
    int fd = socket(AF_UNIX, SOCK_SEQPACKET, 0);
    if (fd < 0) { seterr(d, "socket(AF_UNIX): %s", strerror(errno)); return -1; }

    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    if (strlen(g_mock) >= sizeof sa.sun_path) {
        seterr(d, "socket path is too long (max %zu)", sizeof sa.sun_path - 1);
        close(fd);
        return -1;
    }
    memcpy(sa.sun_path, g_mock, strlen(g_mock));

    if (connect(fd, (struct sockaddr *)&sa, sizeof sa) < 0) {
        seterr(d, "connect %s: %s", g_mock, strerror(errno));
        close(fd);
        return -1;
    }
    d->is_mock = 1;
    if (g_mock_framing) {
        /* The /dev/diag container format over the socket: everything but the
         * ioctls, which a socket does not have. */
        d->transport = DIAG_TP_CHARDEV;
        d->remote = (g_mock_framing == 2);
        snprintf(d->desc, sizeof d->desc, "diagchar%s:%.50s", d->remote ? "-mdm" : "", g_mock);
    } else {
        d->transport = DIAG_TP_MOCK;
        snprintf(d->desc, sizeof d->desc, "%.60s", g_mock);
    }
    qlog("using the unix socket %s as the DIAG transport", d->desc);
    return fd;
}

int diag_open(diag_t *d)
{
    memset(d, 0, sizeof *d);
    d->fd = -1;

    if (g_mock[0]) {
        int fd = open_mock(d);
        if (fd < 0) return -1;
        d->fd = fd;
        return 0;
    }

    /* QRTR first: every modem built into the SoC is reached that way, and
     * it needs no driver state.  Only when the bus has no modem DIAG is the
     * character device tried, which is the external-modem case. */
    int fd = open_qrtr(d);
    if (fd >= 0) { d->fd = fd; return 0; }

    char qrtr_why[256];
    snprintf(qrtr_why, sizeof qrtr_why, "%s", d->last_error);
    qlog("QRTR: %s; trying %s", qrtr_why, DIAG_DEV_PATH);

    fd = open_chardev(d);
    if (fd >= 0) { d->fd = fd; return 0; }

    char dev_why[256];
    snprintf(dev_why, sizeof dev_why, "%s", d->last_error);
    seterr(d, "%.120s; %.120s", qrtr_why, dev_why);
    return -1;
}

void diag_close(diag_t *d)
{
    if (d->fd >= 0) close(d->fd);
    d->fd = -1;
}

/* ---- transfers -------------------------------------------------------- */

int diag_send(diag_t *d, const uint8_t *req, size_t len)
{
    ssize_t w;

    if (d->transport == DIAG_TP_CHARDEV) {
        static uint8_t buf[8 + 2 * (DIAG_MAX_PKT + 2) + 1];
        uint32_t type = USER_SPACE_DATA_TYPE;
        size_t off = 0;
        memcpy(buf, &type, 4);
        off = 4;
        if (d->remote) {
            int32_t token = DIAG_TOKEN_MDM;
            memcpy(buf + off, &token, 4);
            off += 4;
        }
        int n = hdlc_encode(req, len, buf + off, sizeof buf - off);
        if (n < 0) { seterr(d, "request too large (%zu bytes)", len); return -1; }
        w = write(d->fd, buf, off + (size_t)n);
        if (w < 0) {
            seterr(d, "sending to %s failed: %s", diag_transport_desc(d), strerror(errno));
            return -1;
        }
        return 0;
    }

    /* One datagram per request, and no framing: the modem parses the payload
     * as-is and answers with its own wrapper. */
    if (d->transport == DIAG_TP_QRTR) {
        struct sockaddr_qrtr to = { AF_QIPCRTR, d->qrtr_node, d->qrtr_port };
        w = sendto(d->fd, req, len, 0, (struct sockaddr *)&to, sizeof to);
    } else {
        w = send(d->fd, req, len, 0);
    }
    if (w < 0) {
        seterr(d, "sending to %s failed: %s", diag_transport_desc(d), strerror(errno));
        return -1;
    }
    return 0;
}

static int64_t now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

static int matches(const uint8_t *p, int len, int want_cmd, int want_subsys, int want_subcmd)
{
    if (len < 1) return 0;
    if (want_cmd >= 0 && p[0] != (uint8_t)want_cmd) {
        /* A target that dislikes the request answers with an error code and
         * echoes the request; treat that as the answer. */
        if (p[0] >= 0x13 && p[0] <= 0x18) return 1;
        /* A command the security state forbids comes back as 0x42 with the
         * refused command code right behind it -- that is our answer too,
         * and without this the caller would sit there until it timed out. */
        if (p[0] == DIAG_BAD_SEC_MODE_F && len >= 2 && p[1] == (uint8_t)want_cmd) return 1;
        return 0;
    }
    if (want_subsys >= 0) {
        if (len < 4) return 0;
        if (p[1] != (uint8_t)want_subsys) return 0;
        if (want_subcmd >= 0 && (p[2] | (p[3] << 8)) != want_subcmd) return 0;
    }
    return 1;
}

/* Peels the wrappers off a received datagram.  Returns the payload length, or
 * 0 for a datagram that claims a length it does not carry. */
static size_t unwrap(const uint8_t **pp, size_t plen)
{
    const uint8_t *p = *pp;

    /* Newer targets (SM8850 and up) put a 4-byte header in front of every
     * datagram: [u16 type = 8][u16 length of the rest].  SM8350 does not.
     * Strip it only when the length field agrees exactly and the DIAG wrapper
     * really follows, so a payload that happens to start with those bytes is
     * never mistaken for a header. */
    if (plen >= 8) {
        size_t declared = (size_t)p[2] | ((size_t)p[3] << 8);
        if (declared + 4 == plen && p[4] == 0x7E) {
            p += 4;
            plen -= 4;
        }
    }

    /* Then the wrapper itself: 7E 01 <len16> <payload> 7E.  A packet that
     * arrives raw is used whole. */
    if (plen >= 5 && p[0] == 0x7E && p[1] == 0x01) {
        size_t declared = (size_t)p[2] | ((size_t)p[3] << 8);
        if (declared == 0 || declared + 4 > plen) return 0;
        p += 4;
        plen = declared;
    }

    *pp = p;
    return plen;
}

/* Reads one batch from the character device and returns the first frame in
 * it that answers the request, or -1 when none does.  A batch can carry the
 * traffic of every processor: with an external modem only the entries it
 * sent are taken, since the phone's own DSPs keep talking here too. */
static int chardev_take(diag_t *d, uint8_t *out, size_t outsz,
                        int want_cmd, int want_subsys, int want_subcmd)
{
    static uint8_t rx[512 * 1024];
    static uint8_t frame[DIAG_MAX_PKT + 2];

    ssize_t n = read(d->fd, rx, sizeof rx);
    if (n < 8) return -1;

    uint32_t type;
    int32_t count;
    memcpy(&type, rx, 4);
    memcpy(&count, rx + 4, 4);
    if (type != USER_SPACE_DATA_TYPE) return -1;

    size_t off = 8;
    for (int32_t k = 0; k < count && off + 4 <= (size_t)n; k++) {
        int32_t first;
        memcpy(&first, rx + off, 4);
        off += 4;

        int32_t token = 0;
        uint32_t mlen;
        if (first < 0) {                    /* a token: data from an external modem */
            token = first;
            if (off + 4 > (size_t)n) break;
            memcpy(&mlen, rx + off, 4);
            off += 4;
        } else {
            mlen = (uint32_t)first;
        }
        if (mlen > (size_t)n - off) break;
        const uint8_t *e = rx + off;
        off += mlen;

        if ((token != 0) != (d->remote != 0)) { d->rx_dropped++; continue; }

        for (size_t pos = 0; pos < mlen; ) {
            size_t used = 0;
            int plen = hdlc_decode(e + pos, mlen - pos, &used, frame, sizeof frame);
            if (used == 0) break;
            pos += used;
            d->rx_frames++;
            if (plen < 0) { d->rx_dropped++; continue; }

            size_t cl = (size_t)plen > outsz ? outsz : (size_t)plen;
            memcpy(out, frame, cl);
            if (matches(out, (int)cl, want_cmd, want_subsys, want_subcmd)) return (int)cl;
            d->rx_dropped++;
            qlog("dropping unsolicited packet cmd=0x%02x len=%d", out[0], plen);
        }
    }
    return -1;
}

int diag_recv(diag_t *d, uint8_t *out, size_t outsz, int timeout_ms,
              int want_cmd, int want_subsys, int want_subcmd)
{
    static uint8_t rx[512 * 1024];
    int64_t deadline = now_ms() + timeout_ms;

    for (;;) {
        int left = (int)(deadline - now_ms());
        if (left <= 0) { seterr(d, "timed out waiting for a DIAG response"); return -1; }

        struct pollfd pfd = { d->fd, POLLIN, 0 };
        int pr = poll(&pfd, 1, left);
        if (pr < 0) {
            if (errno == EINTR) continue;
            seterr(d, "poll failed: %s", strerror(errno));
            return -1;
        }
        if (pr == 0) { seterr(d, "timed out waiting for a DIAG response"); return -1; }

        if (d->transport == DIAG_TP_CHARDEV) {
            int r = chardev_take(d, out, outsz, want_cmd, want_subsys, want_subcmd);
            if (r >= 0) return r;
            continue;
        }

        ssize_t n = recv(d->fd, rx, sizeof rx, 0);
        if (n <= 0) continue;
        d->rx_frames++;

        const uint8_t *p = rx;
        size_t plen = unwrap(&p, (size_t)n);
        if (plen == 0) { d->rx_dropped++; continue; }

        if (plen > outsz) plen = outsz;
        memcpy(out, p, plen);

        if (matches(out, (int)plen, want_cmd, want_subsys, want_subcmd))
            return (int)plen;

        d->rx_dropped++;
        qlog("dropping unsolicited packet cmd=0x%02x len=%zu", out[0], plen);
    }
}

int diag_xfer(diag_t *d, const uint8_t *req, size_t reqlen,
              uint8_t *out, size_t outsz, int timeout_ms)
{
    if (diag_send(d, req, reqlen) < 0) return -1;

    int want_subsys = -1, want_subcmd = -1;
    if (reqlen >= 4 && req[0] == 0x4B) {
        want_subsys = req[1];
        want_subcmd = req[2] | (req[3] << 8);
    }
    int n = diag_recv(d, out, outsz, timeout_ms, req[0], want_subsys, want_subcmd);
    if (n < 0) return -1;

    /* The target answers a command it dislikes with an error code plus an echo
     * of the request; that is a valid frame but not an answer. */
    if (n >= 1 && out[0] != req[0]) {
        static const char *why[] = {
            "bad command", "bad parameter", "bad length", "bad device",
            "bad mode", "bad SPC mode",
        };
        int idx = out[0] - 0x13;
        const char *reason = (idx >= 0 && idx < 6) ? why[idx] : "unknown error";
        if (out[0] == DIAG_BAD_SEC_MODE_F)
            reason = "the security state of the target does not allow it";
        seterr(d, "the target rejected the command: %s (0x%02x)", reason, out[0]);
        return -1;
    }
    return n;
}
