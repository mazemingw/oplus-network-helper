/* diag.h -- Qualcomm DIAG transport: QRTR first, /dev/diag as the fallback.
 *
 * A modem built into the SoC publishes DIAG as QRTR service 4097, and newer
 * phones no longer ship the diagchar driver (/dev/diag) at all.  An external
 * modem -- the X55 next to a Snapdragon 865/870 -- is different: its DIAG
 * reaches the phone through diagchar as a "remote" processor and never shows
 * up on the bus, so for those the helper falls back to /dev/diag.
 *
 * Over QRTR a request goes out as one raw datagram and the answer comes back
 * wrapped:
 *
 *   7E 01 <len16> <payload> 7E
 *
 * On SM8850 and newer there is a further datagram header in front of that:
 *
 *   [u16 type = 8][u16 length of the rest]
 */
#ifndef QEFS_DIAG_H
#define QEFS_DIAG_H

#include <stddef.h>
#include <stdint.h>

#define DIAG_MAX_PKT   16384

/* DIAG error responses that a target may return instead of a real answer.
 * The 0x13..0x18 block stands on its own; a security refusal instead echoes
 * the rejected command code in the byte after it. */
#define DIAG_BAD_CMD_F        0x13
#define DIAG_BAD_SPC_F        0x18
#define DIAG_BAD_SEC_MODE_F   0x42

enum {
    DIAG_TP_QRTR = 0,      /* AF_QIPCRTR, service 4097                 */
    DIAG_TP_MOCK,          /* a unix socket speaking the QRTR wrapper  */
    DIAG_TP_CHARDEV,       /* /dev/diag (or the test rig's imitation)  */
};

#define QRTR_DIAG_SERVICE 4097

typedef struct {
    int      fd;
    int      transport;
    int      remote;           /* chardev: talking to the external modem    */
    int      is_mock;          /* any of the test rig's transports          */
    uint32_t qrtr_node, qrtr_port;
    char     desc[64];         /* human-readable transport name */
    unsigned long rx_frames;
    unsigned long rx_dropped;
    char     last_error[256];
} diag_t;

/* Talks to a unix SOCK_SEQPACKET socket instead of the QRTR bus, which is how
 * the test harness substitutes a mock modem.  Only the tests pass this. */
void diag_set_mock(const char *path);

/* How the test rig's socket frames DIAG: "qrtr" (the default), "chardev" for
 * the /dev/diag container format, or "mdm" for that format addressed to an
 * external modem. */
void diag_set_mock_framing(const char *name);

int  diag_is_mock(const diag_t *d);

const char *diag_transport_desc(const diag_t *d);

/* Pins the QRTR endpoint instead of discovering it (node:port). */
void diag_set_qrtr(uint32_t node, uint32_t port);

int  diag_open(diag_t *d);
void diag_close(diag_t *d);

/* Watches the DIAG service come and go on the bus, for a caller restarting
 * the modem.  Open the watch before sending the restart; see diag.c. */
int  diag_watch_open(void);
int  diag_watch_cycle(int fd, int quiet_ms, int back_ms);
void diag_watch_close(int fd);

/* One raw DIAG payload out. */
int  diag_send(diag_t *d, const uint8_t *req, size_t len);

/* Waits for a matching answer.  want_cmd is the expected first byte; when
 * want_subsys >= 0 the subsystem id (byte 1) and 16-bit subsystem command
 * (bytes 2-3) are matched too.  Unrelated log/event traffic is dropped.
 * Returns the unwrapped payload length, or -1 on timeout/error. */
int  diag_recv(diag_t *d, uint8_t *out, size_t outsz, int timeout_ms,
               int want_cmd, int want_subsys, int want_subcmd);

/* send + recv in one go. */
int  diag_xfer(diag_t *d, const uint8_t *req, size_t reqlen,
               uint8_t *out, size_t outsz, int timeout_ms);

const char *diag_error(const diag_t *d);

#endif
