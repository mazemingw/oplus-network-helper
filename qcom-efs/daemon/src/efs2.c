#include "efs2.h"
#include "util.h"

#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define DIAG_SUBSYS_CMD_F  0x4B
#define DIAG_NV_READ_F     0x26
#define DIAG_NV_WRITE_F    0x27
#define DIAG_SPC_F         0x41
#define DIAG_SUBSYS_NV     0x30
#define SUBSYS_NV_READ     0x01
#define SUBSYS_NV_WRITE    0x02

/* Opcode numbering as in libopenpst and EfsTools, and as verified against an
 * SM8350 modem.  qfenix's header instead lists 7 unlink / 8 rmdir / 14
 * readlink; with those numbers this modem answers EINVAL to every delete
 * (7 is really readlink, and a readlink on a file is invalid) and, worse,
 * a "readlink" lands on 14, which is RENAME. */
enum {
    EFS2_HELLO = 0, EFS2_QUERY = 1, EFS2_OPEN = 2, EFS2_CLOSE = 3, EFS2_READ = 4,
    EFS2_WRITE = 5, EFS2_SYMLINK = 6, EFS2_READLINK = 7, EFS2_UNLINK = 8,
    EFS2_MKDIR = 9, EFS2_RMDIR = 10, EFS2_OPENDIR = 11, EFS2_READDIR = 12,
    EFS2_CLOSEDIR = 13, EFS2_RENAME = 14, EFS2_STAT = 15, EFS2_LSTAT = 16,
    EFS2_FSTAT = 17, EFS2_CHMOD = 18,
    EFS2_STATFS = 19, EFS2_PUT_V1 = 26, EFS2_GET_V1 = 27, EFS2_DELTREE = 37,
    EFS2_PUT = 38, EFS2_GET = 39,
    EFS2_SYNC_NO_WAIT = 48, EFS2_SYNC_GET_STATUS = 49,
    EFS2_FS_IMAGE_OPEN = 54, EFS2_FS_IMAGE_READ = 55, EFS2_FS_IMAGE_CLOSE = 56,
};

static void eerr(efs_t *e, const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(e->last_error, sizeof e->last_error, fmt, ap);
    va_end(ap);
}

/* Why an exchange produced nothing usable.  diag_xfer already knows -- a
 * timeout, or a target that refused the command outright and said so -- and
 * answering "short reply" to all of it throws that away.  Every call site
 * that checks the length routes its message through here instead. */
static const char *why_short(efs_t *e, int n)
{
    static char buf[288];
    if (n < 0) snprintf(buf, sizeof buf, "%s", diag_error(e->d));
    else snprintf(buf, sizeof buf, "short reply (%d bytes)", n);
    return buf;
}

static void put_le16(uint8_t *p, uint16_t v) { p[0] = v & 0xFF; p[1] = v >> 8; }
static void put_le32(uint8_t *p, uint32_t v)
{
    p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = v >> 24;
}
static uint32_t get_le32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static int32_t get_i32(const uint8_t *p) { return (int32_t)get_le32(p); }
static uint16_t get_le16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }

static void hdr(efs_t *e, uint8_t *cmd, uint8_t op)
{
    cmd[0] = DIAG_SUBSYS_CMD_F;
    cmd[1] = e->method;
    cmd[2] = op;
    cmd[3] = 0x00;
}

void efs_init(efs_t *e, diag_t *d)
{
    memset(e, 0, sizeof *e);
    e->d = d;
    e->method = DIAG_SUBSYS_EFS_STD;
    e->timeout_ms = 4000;
}

/* ---- session ---------------------------------------------------------- */

static int efs_hello(efs_t *e, uint8_t method)
{
    uint8_t cmd[4 + 0x28];
    uint8_t resp[512];

    memset(cmd, 0, sizeof cmd);
    cmd[0] = DIAG_SUBSYS_CMD_F;
    cmd[1] = method;
    cmd[2] = EFS2_HELLO;
    cmd[3] = 0;
    for (int i = 0; i < 6; i++) put_le32(cmd + 4 + i * 4, 0x100000); /* window sizes */
    put_le32(cmd + 28, 1);            /* version     */
    put_le32(cmd + 32, 1);            /* min version */
    put_le32(cmd + 36, 1);            /* max version */
    put_le32(cmd + 40, 0xFFFFFFFF);   /* feature bits */

    int n = diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
    if (n < 4 || resp[0] != DIAG_SUBSYS_CMD_F || resp[1] != method) return -1;
    return 0;
}

static void efs_query(efs_t *e)
{
    uint8_t cmd[4], resp[128];
    hdr(e, cmd, EFS2_QUERY);
    diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
}

int efs_detect(efs_t *e)
{
    static const uint8_t order[2] = { DIAG_SUBSYS_EFS_ALT, DIAG_SUBSYS_EFS_STD };

    for (int i = 0; i < 2; i++) {
        if (efs_hello(e, order[i]) == 0) {
            e->method = order[i];
            e->detected = 1;
            qlog("EFS2 reachable through subsystem 0x%02x", e->method);
            efs_query(e);
            return 0;
        }
    }
    eerr(e, "EFS2 did not answer on subsystem 0x3E or 0x13 (%s)", diag_error(e->d));
    return -1;
}

static int need_session(efs_t *e)
{
    if (e->detected) return 0;
    return efs_detect(e);
}

/* ---- directories ------------------------------------------------------ */

int efs_opendir(efs_t *e, const char *path)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[256];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_OPENDIR);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 12) { eerr(e, "opendir '%s': %s", path, why_short(e, n)); return -1; }

    int32_t dirp = get_i32(resp + 4);
    e->last_errno = get_i32(resp + 8);
    if (e->last_errno != 0 || dirp < 0) {
        eerr(e, "opendir '%s' failed, efs errno=%d", path, e->last_errno);
        return -1;
    }
    return dirp;
}

int efs_readdir(efs_t *e, int32_t dirp, uint32_t seqno, efs_dirent_t *ent)
{
    uint8_t cmd[12], resp[1024];

    hdr(e, cmd, EFS2_READDIR);
    put_le32(cmd + 4, (uint32_t)dirp);
    put_le32(cmd + 8, seqno);

    int n = diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
    if (n < 40) { eerr(e, "readdir: %s", why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 12);
    if (e->last_errno != 0) { eerr(e, "readdir: efs errno=%d", e->last_errno); return -1; }

    memset(ent, 0, sizeof *ent);
    ent->entry_type = get_i32(resp + 16);
    ent->mode       = get_i32(resp + 20);
    ent->size       = get_i32(resp + 24);
    ent->atime      = get_i32(resp + 28);
    ent->mtime      = get_i32(resp + 32);
    ent->ctime      = get_i32(resp + 36);

    /* The end of a directory is an entry with no name -- NOT entry_type 0.
     * On SM8350 modems entry_type 0 means "regular file" (1 is a directory,
     * 15 an item file), so stopping at type 0 truncates the listing at the
     * first file. */
    if (n <= 40) return 1;

    if (n > 40) {
        size_t nl = (size_t)n - 40;
        if (nl >= sizeof ent->name) nl = sizeof ent->name - 1;
        memcpy(ent->name, resp + 40, nl);
        ent->name[nl] = 0;
        /* the modem pads with NULs; keep only the first component */
        ent->name[strnlen(ent->name, sizeof ent->name - 1)] = 0;
    }
    if (!ent->name[0]) return 1;
    return 0;
}

void efs_closedir(efs_t *e, int32_t dirp)
{
    uint8_t cmd[8], resp[128];
    hdr(e, cmd, EFS2_CLOSEDIR);
    put_le32(cmd + 4, (uint32_t)dirp);
    diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
}

/* ---- metadata --------------------------------------------------------- */

int efs_stat(efs_t *e, const char *path, efs_stat_t *st)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[256];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_STAT);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 32) { eerr(e, "stat '%s': %s", path, why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) { eerr(e, "stat '%s': efs errno=%d", path, e->last_errno); return -1; }

    st->mode  = get_i32(resp + 8);
    st->size  = get_i32(resp + 12);
    st->nlink = get_i32(resp + 16);
    st->atime = get_i32(resp + 20);
    st->mtime = get_i32(resp + 24);
    st->ctime = get_i32(resp + 28);
    return 0;
}

/* Like stat, but describes a symlink itself instead of what it points at --
 * EFS2's stat follows links, exactly as POSIX stat() does. */
int efs_lstat(efs_t *e, const char *path, efs_stat_t *st)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[256];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_LSTAT);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 32) { eerr(e, "lstat '%s': %s", path, why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) { eerr(e, "lstat '%s': efs errno=%d", path, e->last_errno); return -1; }

    st->mode  = get_i32(resp + 8);
    st->size  = get_i32(resp + 12);
    st->nlink = get_i32(resp + 16);
    st->atime = get_i32(resp + 20);
    st->mtime = get_i32(resp + 24);
    st->ctime = get_i32(resp + 28);
    return 0;
}

int efs_statfs(efs_t *e, const char *path, uint8_t *raw, size_t rawsz, int *rawlen)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[512];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_STATFS);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "statfs: %s", why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) { eerr(e, "statfs: efs errno=%d", e->last_errno); return -1; }

    size_t body = (size_t)n - 8;
    if (body > rawsz) body = rawsz;
    memcpy(raw, resp + 8, body);
    *rawlen = (int)body;
    return 0;
}

/* ---- files ------------------------------------------------------------ */

int efs_open(efs_t *e, const char *path, int32_t oflag, int32_t mode)
{
    uint8_t cmd[12 + EFS_PATH_MAX], resp[128];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_OPEN);
    put_le32(cmd + 4, (uint32_t)oflag);
    put_le32(cmd + 8, (uint32_t)mode);
    memcpy(cmd + 12, path, plen);

    int n = diag_xfer(e->d, cmd, 12 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 12) { eerr(e, "open '%s': %s", path, why_short(e, n)); return -1; }

    int32_t fd = get_i32(resp + 4);
    e->last_errno = get_i32(resp + 8);
    if (fd < 0 || e->last_errno != 0) {
        eerr(e, "open '%s' failed (fd=%d, efs errno=%d, oflag=0x%x)",
             path, fd, e->last_errno, oflag);
        return -1;
    }
    return fd;
}

int efs_read(efs_t *e, int32_t fd, uint32_t nbytes, uint32_t offset,
             uint8_t *buf, size_t bufsz)
{
    uint8_t cmd[16], resp[64 + EFS_MAX_IO * 2];

    if (nbytes > EFS_MAX_IO) nbytes = EFS_MAX_IO;

    hdr(e, cmd, EFS2_READ);
    put_le32(cmd + 4, (uint32_t)fd);
    put_le32(cmd + 8, nbytes);
    put_le32(cmd + 12, offset);

    int n = diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
    if (n < 20) { eerr(e, "read: %s", why_short(e, n)); return -1; }

    int32_t got = get_i32(resp + 12);
    e->last_errno = get_i32(resp + 16);
    if (e->last_errno != 0 || got < 0) {
        eerr(e, "read at %u: efs errno=%d", offset, e->last_errno);
        return -1;
    }
    if ((size_t)got > bufsz) got = (int32_t)bufsz;
    if ((size_t)got > (size_t)n - 20) got = n - 20;
    if (got > 0) memcpy(buf, resp + 20, (size_t)got);
    return got;
}

int efs_write(efs_t *e, int32_t fd, uint32_t offset, const uint8_t *data, uint32_t len)
{
    uint8_t cmd[12 + EFS_MAX_IO], resp[128];

    if (len > EFS_MAX_IO) len = EFS_MAX_IO;

    hdr(e, cmd, EFS2_WRITE);
    put_le32(cmd + 4, (uint32_t)fd);
    put_le32(cmd + 8, offset);
    memcpy(cmd + 12, data, len);

    int n = diag_xfer(e->d, cmd, 12 + len, resp, sizeof resp, e->timeout_ms);
    if (n < 20) { eerr(e, "write: %s", why_short(e, n)); return -1; }

    int32_t wrote = get_i32(resp + 12);
    e->last_errno = get_i32(resp + 16);
    if (e->last_errno != 0 || wrote < 0) {
        eerr(e, "write at %u: efs errno=%d", offset, e->last_errno);
        return -1;
    }
    if ((uint32_t)wrote > len) wrote = (int32_t)len;   /* some modems over-report */
    return wrote;
}

void efs_close_fd(efs_t *e, int32_t fd)
{
    uint8_t cmd[8], resp[128];
    hdr(e, cmd, EFS2_CLOSE);
    put_le32(cmd + 4, (uint32_t)fd);
    diag_xfer(e->d, cmd, sizeof cmd, resp, sizeof resp, e->timeout_ms);
}

/* ---- namespace -------------------------------------------------------- */

static int simple_path_cmd(efs_t *e, uint8_t op, const char *path, const char *what)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[128];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, op);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "%s '%s': %s", what, path, why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) {
        eerr(e, "%s '%s' failed, efs errno=%d", what, path, e->last_errno);
        return -1;
    }
    return 0;
}

static int mode_path_cmd(efs_t *e, uint8_t op, const char *path, int16_t mode,
                         const char *what, int tolerate_eexist)
{
    uint8_t cmd[6 + EFS_PATH_MAX], resp[128];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, op);
    put_le16(cmd + 4, (uint16_t)mode);
    memcpy(cmd + 6, path, plen);

    int n = diag_xfer(e->d, cmd, 6 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "%s '%s': %s", what, path, why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno == 0) return 0;
    /* 17 = EEXIST, 6 = ENXIO (a virtual mount point) */
    if (tolerate_eexist && (e->last_errno == 17 || e->last_errno == 6)) return 0;

    eerr(e, "%s '%s' failed, efs errno=%d", what, path, e->last_errno);
    return -1;
}

int efs_unlink(efs_t *e, const char *path) { return simple_path_cmd(e, EFS2_UNLINK, path, "unlink"); }
int efs_rmdir(efs_t *e, const char *path)  { return simple_path_cmd(e, EFS2_RMDIR, path, "rmdir"); }
int efs_mkdir(efs_t *e, const char *path, int16_t mode)
{
    return mode_path_cmd(e, EFS2_MKDIR, path, mode, "mkdir", 1);
}
int efs_chmod(efs_t *e, const char *path, int16_t mode)
{
    return mode_path_cmd(e, EFS2_CHMOD, path, mode, "chmod", 0);
}

int efs_mkdirp(efs_t *e, const char *path)
{
    char buf[EFS_PATH_MAX];
    size_t len = strlen(path);

    if (len >= sizeof buf) { eerr(e, "path too long"); return -1; }
    memcpy(buf, path, len + 1);

    for (char *p = buf + 1; *p; p++) {
        if (*p != '/') continue;
        *p = 0;
        efs_mkdir(e, buf, 0x1FF);   /* best effort; EEXIST is fine */
        *p = '/';
    }
    return 0;
}

int efs_symlink(efs_t *e, const char *target, const char *linkpath)
{
    uint8_t cmd[4 + 2 * EFS_PATH_MAX], resp[128];
    size_t tl = strlen(target) + 1, ll = strlen(linkpath) + 1;

    if (need_session(e) < 0) return -1;
    if (tl + ll > 508) { eerr(e, "paths too long"); return -1; }

    hdr(e, cmd, EFS2_SYMLINK);
    memcpy(cmd + 4, target, tl);
    memcpy(cmd + 4 + tl, linkpath, ll);

    int n = diag_xfer(e->d, cmd, 4 + tl + ll, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "symlink: %s", why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) { eerr(e, "symlink failed, efs errno=%d", e->last_errno); return -1; }
    return 0;
}

/* Two NUL-terminated paths, exactly like symlink. */
int efs_rename(efs_t *e, const char *from, const char *to)
{
    uint8_t cmd[4 + 2 * EFS_PATH_MAX], resp[128];
    size_t fl = strlen(from) + 1, tl = strlen(to) + 1;

    if (need_session(e) < 0) return -1;
    if (fl + tl > 508) { eerr(e, "paths too long"); return -1; }

    hdr(e, cmd, EFS2_RENAME);
    memcpy(cmd + 4, from, fl);
    memcpy(cmd + 4 + fl, to, tl);

    int n = diag_xfer(e->d, cmd, 4 + fl + tl, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "rename: %s", why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) {
        eerr(e, "rename '%s' -> '%s' failed, efs errno=%d", from, to, e->last_errno);
        return -1;
    }
    return 0;
}

int efs_readlink(efs_t *e, const char *path, char *buf, size_t bufsz)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[1024];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, EFS2_READLINK);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 8) { eerr(e, "readlink: %s", why_short(e, n)); return -1; }

    e->last_errno = get_i32(resp + 4);
    if (e->last_errno != 0) { eerr(e, "readlink failed, efs errno=%d", e->last_errno); return -1; }

    size_t tl = (n > 8) ? (size_t)n - 8 : 0;
    if (tl >= bufsz) tl = bufsz - 1;
    memcpy(buf, resp + 8, tl);
    buf[tl] = 0;
    return 0;
}

/* ---- item interface --------------------------------------------------- */

static int get_item_op(efs_t *e, uint8_t op, const char *path,
                       uint8_t *buf, size_t bufsz, int32_t *len_out)
{
    uint8_t cmd[4 + EFS_PATH_MAX], resp[8192];
    size_t plen = strlen(path) + 1;

    if (plen > 252) { eerr(e, "path too long"); return -1; }

    hdr(e, cmd, op);
    memcpy(cmd + 4, path, plen);

    int n = diag_xfer(e->d, cmd, 4 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 12) { eerr(e, "get item '%s': %s", path, why_short(e, n)); return -1; }

    int32_t dlen = get_i32(resp + 4);
    e->last_errno = get_i32(resp + 8);
    if (e->last_errno != 0 || dlen < 0) {
        eerr(e, "get item '%s': efs errno=%d", path, e->last_errno);
        return -1;
    }
    if ((size_t)dlen > bufsz || (size_t)dlen > (size_t)n - 12) {
        eerr(e, "get item '%s': declared %d bytes, packet holds %d", path, dlen, n - 12);
        return -1;
    }
    if (dlen > 0) memcpy(buf, resp + 12, (size_t)dlen);
    if (len_out) *len_out = dlen;
    return 0;
}

/* GET (39) is not the path-only deprecated GET (27): it carries the exact
 * item length, path length and sequence; the reply has a 14-byte header.
 * Verified with read-only requests on PLK110. Never infer an item's contents
 * or length from a guessed default. */
static int get_item_modern(efs_t *e, const char *path, uint8_t *buf, size_t bufsz,
                           int32_t size, int32_t *len)
{
    uint8_t cmd[14 + EFS_PATH_MAX], resp[8192];
    size_t plen = strlen(path) + 1;
    if (plen > 252 || size < 0 || (size_t)size > bufsz || (size_t)size > sizeof resp - 14) {
        eerr(e, "get item '%s': invalid item size or path", path);
        return -1;
    }
    hdr(e, cmd, EFS2_GET);
    put_le32(cmd + 4, (uint32_t)size);
    put_le32(cmd + 8, (uint32_t)plen);
    put_le16(cmd + 12, 0);
    memcpy(cmd + 14, path, plen);
    int n = diag_xfer(e->d, cmd, 14 + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 14) { eerr(e, "get item '%s': %s", path, why_short(e, n)); return -1; }
    int32_t dlen = get_i32(resp + 4);
    e->last_errno = get_i32(resp + 8);
    if (e->last_errno != 0 || dlen < 0) {
        eerr(e, "get item '%s': efs errno=%d", path, e->last_errno);
        return -1;
    }
    if (get_le16(resp + 12) != 0 || dlen != size || (size_t)n != 14 + (size_t)dlen) {
        eerr(e, "get item '%s': mismatched sequence, size or incomplete payload", path);
        return -1;
    }
    if (dlen) memcpy(buf, resp + 14, (size_t)dlen);
    if (len) *len = dlen;
    return 0;
}

static int32_t item_size(efs_t *e, const char *path)
{
    efs_stat_t st;
    if (efs_stat(e, path, &st) == 0 && st.size >= 0) return st.size;
    /* Some item paths reject STAT but are described by their parent listing. */
    char parent[EFS_PATH_MAX];
    if (strlen(path) >= sizeof parent) return -1;
    strcpy(parent, path);
    char *slash = strrchr(parent, '/');
    if (!slash || !slash[1]) return -1;
    const char *name = strrchr(path, '/') + 1;
    if (slash == parent) slash[1] = 0; else *slash = 0;
    int dir = efs_opendir(e, parent);
    if (dir < 0) return -1;
    int32_t size = -1;
    for (uint32_t seq = 1; seq <= 65536; seq++) {
        efs_dirent_t ent;
        if (efs_readdir(e, dir, seq, &ent) != 0) break;
        if (!strcmp(ent.name, name)) { size = ent.size; break; }
    }
    efs_closedir(e, dir);
    return size;
}

static int get_item_sized(efs_t *e, const char *path, uint8_t *buf, size_t bufsz,
                          int32_t *len, int32_t size_hint)
{
    if (need_session(e) < 0) return -1;
    int32_t size = size_hint >= 0 ? size_hint : item_size(e, path);
    char modern_error[sizeof e->last_error];
    int modern_errno = 0;
    int tried_modern = size >= 0 && (size_t)size <= bufsz && size <= 8178;
    if (tried_modern) {
        if (get_item_modern(e, path, buf, bufsz, size, len) == 0) return 0;
        memcpy(modern_error, e->last_error, sizeof modern_error);
        modern_errno = e->last_errno;
    }
    /* Retain compatibility with older path-only implementations. If all
     * forms fail, keep the correctly formed modern request's real error. */
    if (get_item_op(e, EFS2_GET, path, buf, bufsz, len) == 0) return 0;
    if (get_item_op(e, EFS2_GET_V1, path, buf, bufsz, len) == 0) return 0;
    if (tried_modern) {
        memcpy(e->last_error, modern_error, sizeof e->last_error);
        e->last_errno = modern_errno;
    }
    return -1;
}

int efs_get_item(efs_t *e, const char *path, uint8_t *buf, size_t bufsz, int32_t *len)
{
    return get_item_sized(e, path, buf, bufsz, len, -1);
}

/* PUT (opcode 38) writes an item file atomically:
 *   [hdr 4][data_len u16][pad 2][flags i32][mode i16][data][path\0]
 * and answers with [hdr 4][perm i16][errno i16][written i16].
 * The deprecated opcode 26 hangs some modems -- never fall back to it. */
int efs_put_item(efs_t *e, const char *path, const uint8_t *data, int32_t len,
                 int32_t flags, int16_t mode)
{
    static uint8_t cmd[16 + 8192 + EFS_PATH_MAX];
    uint8_t resp[128];
    size_t plen = strlen(path) + 1;

    if (need_session(e) < 0) return -1;
    if (len < 0 || (size_t)len + plen + 14 > sizeof cmd) { eerr(e, "item too large"); return -1; }

    memset(cmd, 0, 14);
    hdr(e, cmd, EFS2_PUT);
    put_le16(cmd + 4, (uint16_t)len);
    put_le32(cmd + 8, (uint32_t)flags);
    put_le16(cmd + 12, (uint16_t)mode);
    memcpy(cmd + 14, data, (size_t)len);
    memcpy(cmd + 14 + len, path, plen);

    int n = diag_xfer(e->d, cmd, 14 + (size_t)len + plen, resp, sizeof resp, e->timeout_ms);
    if (n < 10) { eerr(e, "put item '%s': %s", path, why_short(e, n)); return -1; }

    e->last_errno = (int16_t)get_le16(resp + 6);
    if (e->last_errno != 0) {
        eerr(e, "put item '%s': efs errno=%d", path, e->last_errno);
        return -1;
    }
    return 0;
}

int efs_is_item_path(const char *path)
{
    return strncmp(path, "/nv/item_files/", 15) == 0 ||
           strncmp(path, "/nv/reg_files/", 14) == 0 ||
           strncmp(path, "/cgps/nv/item_files/", 20) == 0 ||
           strncmp(path, "/sd/", 4) == 0;
}

/* ---- journal sync ----------------------------------------------------- */

/* Ceiling on how long the commit is waited for.  It only ever costs anything
 * on a target whose status field is broken (see below); one that answers
 * properly returns as soon as it says "done".  Measured on the SM8350: a file
 * written, SYNC_NO_WAIT acknowledged, and the modem restarted with *no* wait
 * at all still comes back with the new contents -- so the commit is already
 * ordered ahead of what follows and a long ceiling buys nothing.  It is kept
 * short for that reason: every delete, mkdir, chmod and save commits, and a
 * ceiling that is always spent is a pause on every single one of them. */
#define SYNC_POLL_TRIES 3

/* How many times the commit start is retried past a "still settling" refusal.
 * Measured on the SM8350: with something to commit the modem never refuses,
 * even with no pause at all between one commit and the next -- the refusal
 * belongs to commits with nothing to write, and those are now skipped before
 * they reach the modem.  So this stays short: a refusal that does happen is
 * worth reporting promptly rather than sitting on. */
#define SYNC_START_TRIES 6

/*
 * Commits the EFS journal, which is what makes a write outlive a modem
 * restart: until it runs, a write only exists in the modem's journal and a
 * restart quietly rolls the file back.
 *
 * SYNC_NO_WAIT starts the commit and hands back a token, and SYNC_GET_STATUS
 * is supposed to say when it has finished.  On the SM8350 that status never
 * settles on "done" even though the commit plainly happened -- measured
 * directly: a file written, synced, and carried through a modem restart comes
 * back with the new contents, while the same file without the sync rolls back.
 * So the poll is a best-effort wait rather than a verdict, and only the modem
 * refusing to start a commit at all is treated as a failure.
 */
int efs_sync(efs_t *e)
{
    uint8_t cmd[16], resp[128];

    if (need_session(e) < 0) return -1;

    uint32_t token = 0;
    int32_t err = 0;

    /* Starting a commit while one is still settling is refused (efs errno 306
     * on the SM8350), and that happens routinely: saving a file commits, and
     * restarting the modem straight afterwards commits again.  It is
     * transient -- measured, the very next call comes back clean -- so keep
     * asking for a moment instead of calling the whole thing off. */
    for (int attempt = 0; attempt < SYNC_START_TRIES; attempt++) {
        memset(cmd, 0, sizeof cmd);
        hdr(e, cmd, EFS2_SYNC_NO_WAIT);
        put_le16(cmd + 4, 1);
        cmd[6] = '/';
        cmd[7] = 0;

        int n = diag_xfer(e->d, cmd, 8, resp, sizeof resp, e->timeout_ms);
        if (n < 14) { eerr(e, "sync: %s", why_short(e, n)); return -1; }

        token = get_le32(resp + 6);
        err = get_i32(resp + 10);
        if (err == 0) break;
        usleep(200000);
    }
    if (err != 0) { eerr(e, "sync start failed, efs errno=%d", err); return -1; }

    for (int i = 0; i < SYNC_POLL_TRIES; i++) {
        usleep(100000);
        memset(cmd, 0, sizeof cmd);
        hdr(e, cmd, EFS2_SYNC_GET_STATUS);
        put_le16(cmd + 4, 1);
        put_le32(cmd + 6, token);
        cmd[10] = '/';
        cmd[11] = 0;

        int n = diag_xfer(e->d, cmd, 12, resp, sizeof resp, e->timeout_ms);
        if (n < 11) continue;
        if (resp[6] == 0) return 0;
    }
    qlog("sync: the modem never said the commit finished; it was started and "
         "given %d ms, which is how this target behaves", SYNC_POLL_TRIES * 100);
    return 0;
}

/* ---- whole-file helpers ----------------------------------------------- */

int efs_read_file(efs_t *e, const char *path, uint8_t **out, size_t *len)
{
    return efs_read_file_sized(e, path, out, len, -1);
}

/* size_hint spares the stat: a caller that has just listed the directory
 * already knows how big the file is, and on a tree walk that stat is one
 * round trip in four.  Pass -1 when the size is not known.  A known size of
 * zero also spares the read -- marker files under /nv/item_files are empty on
 * purpose and there is nothing to ask for. */
int efs_read_file_sized(efs_t *e, const char *path, uint8_t **out, size_t *len,
                        int32_t size_hint)
{
    efs_stat_t st;
    *out = NULL;
    *len = 0;

    if (need_session(e) < 0) return -1;

    int32_t fd = efs_open(e, path, EFS_O_RDONLY, 0);
    if (fd < 0) {
        /* Item files are invisible to the file interface -- try GET. */
        uint8_t *buf = malloc(8192);
        int32_t ilen = 0;
        if (!buf) { eerr(e, "out of memory"); return -1; }
        if (get_item_sized(e, path, buf, 8192, &ilen, size_hint) == 0) {
            *out = buf;
            *len = (size_t)ilen;
            return 0;
        }
        free(buf);
        return -1;
    }

    size_t size = 0;
    int known = 0;
    if (size_hint >= 0) {
        size = (size_t)size_hint;
        known = 1;
    } else if (efs_stat(e, path, &st) == 0 && st.size > 0) {
        size = (size_t)st.size;
        known = 1;
    }

    size_t cap = size ? size : 4096;
    uint8_t *buf = malloc(cap ? cap : 1);
    if (!buf) { efs_close_fd(e, fd); eerr(e, "out of memory"); return -1; }

    size_t off = 0;
    for (;;) {
        if (known && off >= size) break;
        if (off + EFS_MAX_IO > cap) {
            size_t ncap = cap * 2 + EFS_MAX_IO;
            uint8_t *nb = realloc(buf, ncap);
            if (!nb) { free(buf); efs_close_fd(e, fd); eerr(e, "out of memory"); return -1; }
            buf = nb;
            cap = ncap;
        }
        uint32_t want = EFS_MAX_IO;
        if (known && size - off < want) want = (uint32_t)(size - off);

        int got = efs_read(e, fd, want, (uint32_t)off, buf + off, cap - off);
        if (got < 0) { free(buf); efs_close_fd(e, fd); return -1; }
        if (got == 0) break;
        off += (size_t)got;
        if (!known && (uint32_t)got < want) break;  /* unknown size: short read = EOF */
    }

    efs_close_fd(e, fd);
    if (known && off != size) {
        free(buf);
        eerr(e, "read '%s': expected %zu bytes, got %zu (file changed or short read)", path, size, off);
        return -1;
    }
    *out = buf;
    *len = off;
    return 0;
}

int efs_write_file(efs_t *e, const char *path, const uint8_t *data, size_t len,
                   int16_t mode, int force_item)
{
    if (need_session(e) < 0) return -1;

    /* The path only suggests the type; an explicit choice wins. */
    int item = (force_item < 0) ? efs_is_item_path(path) : (force_item != 0);

    /* The atomic item interface is only correct for item paths.  Used on an
     * ordinary path it stores the file as an item file (mode 0160777, with the
     * requested mode ignored), which is not what the caller asked for. */
    if (item && len <= 6144) {
        int32_t flags = EFS_O_CREAT | EFS_O_WRONLY | EFS_O_TRUNC |
                        EFS_O_ITEMFILE | EFS_O_AUTODIR;
        if (efs_put_item(e, path, data, (int32_t)len, flags, mode) == 0) return 0;
    }

    efs_mkdirp(e, path);

    int32_t fd = -1;
    if (item) {
        /* Item paths need mode 0160xxx; do not combine with O_AUTODIR --
         * that combination is known to crash some modems. */
        fd = efs_open(e, path, EFS_O_WRONLY | EFS_O_CREAT | EFS_O_TRUNC | EFS_O_ITEMFILE, mode);
    }
    if (fd < 0)
        fd = efs_open(e, path, EFS_O_WRONLY | EFS_O_CREAT | EFS_O_TRUNC | EFS_O_AUTODIR, mode);
    if (fd < 0) return -1;

    size_t off = 0;
    while (off < len) {
        uint32_t chunk = (len - off > EFS_MAX_IO) ? EFS_MAX_IO : (uint32_t)(len - off);
        int w = efs_write(e, fd, (uint32_t)off, data + off, chunk);
        if (w <= 0) { efs_close_fd(e, fd); return -1; }
        off += (size_t)w;
    }
    efs_close_fd(e, fd);
    return 0;
}

/* ---- modem-generated tar image ---------------------------------------- */

static void image_close(efs_t *e, int32_t handle)
{
    uint8_t cl[8], resp[128];
    hdr(e, cl, EFS2_FS_IMAGE_CLOSE);
    put_le32(cl + 4, (uint32_t)handle);
    diag_xfer(e->d, cl, sizeof cl, resp, sizeof resp, e->timeout_ms);
}

int efs_image_dump(efs_t *e, const char *efs_path, const char *local_path,
                   uint64_t *bytes_out)
{
    uint8_t cmd[8 + EFS_PATH_MAX], resp[4096];
    size_t plen = strlen(efs_path) + 1;

    if (need_session(e) < 0) return -1;
    if (plen > 250) { eerr(e, "path too long"); return -1; }

    memset(cmd, 0, 8);
    hdr(e, cmd, EFS2_FS_IMAGE_OPEN);
    put_le16(cmd + 4, 0);      /* sequence  */
    cmd[6] = 0;                /* 0 = TAR   */
    memcpy(cmd + 7, efs_path, plen);

    int n = diag_xfer(e->d, cmd, 7 + plen, resp, sizeof resp, 15000);
    if (n < 12) { eerr(e, "fs-image open: %s", why_short(e, n)); return -1; }

    int32_t handle = get_i32(resp + 4);
    e->last_errno = get_i32(resp + 8);
    if (handle < 0 || e->last_errno != 0) {
        eerr(e, "fs-image open failed (handle=%d, efs errno=%d)", handle, e->last_errno);
        return -1;
    }

    int out = open(local_path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (out >= 0) fchmod(out, 0666);
    if (out < 0) { eerr(e, "cannot create %s: %s", local_path, strerror(errno)); goto close_handle; }

    uint64_t total = 0;
    for (uint16_t seq = 0; ; seq++) {
        uint8_t rd[10];
        hdr(e, rd, EFS2_FS_IMAGE_READ);
        put_le32(rd + 4, (uint32_t)handle);
        put_le16(rd + 8, seq);

        n = diag_xfer(e->d, rd, sizeof rd, resp, sizeof resp, 15000);
        if (n < 15) { eerr(e, "fs-image read at seq %u: %s", seq, why_short(e, n)); close(out); goto close_handle; }

        e->last_errno = get_i32(resp + 10);
        if (e->last_errno != 0) {
            eerr(e, "fs-image read: efs errno=%d", e->last_errno);
            close(out);
            goto close_handle;
        }
        int end = resp[14] != 0;

        if (n > 15) {
            size_t dl = (size_t)n - 15;
            if (write(out, resp + 15, dl) != (ssize_t)dl) {
                eerr(e, "local write failed: %s", strerror(errno));
                close(out);
                goto close_handle;
            }
            total += dl;
        }
        if (end) break;
    }
    close(out);

    image_close(e, handle);
    if (bytes_out) *bytes_out = total;
    return 0;

close_handle:
    image_close(e, handle);
    return -1;
}

/* ---- NV items --------------------------------------------------------- */

const char *nv_status_str(uint16_t s)
{
    switch (s) {
    case 0: return "OK";
    case 1: return "busy";
    case 2: return "bad command";
    case 3: return "NV full";
    case 4: return "failed";
    case 5: return "not active";
    case 6: return "bad parameter";
    case 7: return "read-only";
    case 8: return "not defined";
    default: return "unknown";
    }
}

#define NV_PKT_SIZE (1 + 2 + NV_ITEM_DATA_SIZE + 2)

int nv_read(diag_t *d, uint16_t item, uint8_t *data, uint16_t *status, int timeout_ms)
{
    uint8_t cmd[NV_PKT_SIZE], resp[NV_PKT_SIZE + 32];

    memset(cmd, 0, sizeof cmd);
    cmd[0] = DIAG_NV_READ_F;
    put_le16(cmd + 1, item);

    int n = diag_xfer(d, cmd, sizeof cmd, resp, sizeof resp, timeout_ms);
    if (n < NV_PKT_SIZE || resp[0] != DIAG_NV_READ_F) return -1;

    memcpy(data, resp + 3, NV_ITEM_DATA_SIZE);
    *status = get_le16(resp + 3 + NV_ITEM_DATA_SIZE);
    return 0;
}

int nv_write(diag_t *d, uint16_t item, const uint8_t *data, size_t len,
             uint16_t *status, int timeout_ms)
{
    uint8_t cmd[NV_PKT_SIZE], resp[NV_PKT_SIZE + 32];

    if (len > NV_ITEM_DATA_SIZE) len = NV_ITEM_DATA_SIZE;
    memset(cmd, 0, sizeof cmd);
    cmd[0] = DIAG_NV_WRITE_F;
    put_le16(cmd + 1, item);
    memcpy(cmd + 3, data, len);

    int n = diag_xfer(d, cmd, sizeof cmd, resp, sizeof resp, timeout_ms);
    if (n < NV_PKT_SIZE || resp[0] != DIAG_NV_WRITE_F) return -1;

    *status = get_le16(resp + 3 + NV_ITEM_DATA_SIZE);
    return 0;
}

#define NV_SUB_PKT_SIZE (4 + 2 + 2 + NV_ITEM_DATA_SIZE + 2)

int nv_read_sub(diag_t *d, uint16_t item, uint16_t index, uint8_t *data,
                uint16_t *status, int timeout_ms)
{
    uint8_t cmd[NV_SUB_PKT_SIZE], resp[NV_SUB_PKT_SIZE + 32];

    memset(cmd, 0, sizeof cmd);
    cmd[0] = DIAG_SUBSYS_CMD_F;
    cmd[1] = DIAG_SUBSYS_NV;
    cmd[2] = SUBSYS_NV_READ;
    cmd[3] = 0;
    put_le16(cmd + 4, item);
    put_le16(cmd + 6, index);

    int n = diag_xfer(d, cmd, sizeof cmd, resp, sizeof resp, timeout_ms);
    if (n < (int)sizeof cmd || resp[0] != DIAG_SUBSYS_CMD_F) return -1;

    memcpy(data, resp + 8, NV_ITEM_DATA_SIZE);
    *status = get_le16(resp + 8 + NV_ITEM_DATA_SIZE);
    return 0;
}

int nv_write_sub(diag_t *d, uint16_t item, uint16_t index, const uint8_t *data,
                 size_t len, uint16_t *status, int timeout_ms)
{
    uint8_t cmd[NV_SUB_PKT_SIZE], resp[NV_SUB_PKT_SIZE + 32];

    if (len > NV_ITEM_DATA_SIZE) len = NV_ITEM_DATA_SIZE;
    memset(cmd, 0, sizeof cmd);
    cmd[0] = DIAG_SUBSYS_CMD_F;
    cmd[1] = DIAG_SUBSYS_NV;
    cmd[2] = SUBSYS_NV_WRITE;
    cmd[3] = 0;
    put_le16(cmd + 4, item);
    put_le16(cmd + 6, index);
    memcpy(cmd + 8, data, len);

    int n = diag_xfer(d, cmd, sizeof cmd, resp, sizeof resp, timeout_ms);
    if (n < (int)sizeof cmd || resp[0] != DIAG_SUBSYS_CMD_F) return -1;

    *status = get_le16(resp + 8 + NV_ITEM_DATA_SIZE);
    return 0;
}

int diag_spc(diag_t *d, const char *spc, int *ok, int timeout_ms)
{
    uint8_t cmd[1 + 6], resp[32];

    cmd[0] = DIAG_SPC_F;
    memcpy(cmd + 1, spc, 6);          /* six ASCII digits, no terminator */

    int n = diag_xfer(d, cmd, sizeof cmd, resp, sizeof resp, timeout_ms);
    if (n < 2 || resp[0] != DIAG_SPC_F) return -1;

    /* The answer is 0x41 followed by one byte: non-zero means the code was
     * accepted and the security level is now raised. */
    *ok = resp[1] != 0;
    return 0;
}
