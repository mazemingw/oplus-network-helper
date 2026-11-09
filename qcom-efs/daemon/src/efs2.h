/* efs2.h -- EFS2 file operations carried inside DIAG subsystem packets.
 *
 * Packet layouts follow qfenix (BSD-3-Clause, iamromulan) and libopenpst,
 * which are the two implementations known to work against shipping modems.
 */
#ifndef QEFS_EFS2_H
#define QEFS_EFS2_H

#include "diag.h"

#define EFS_NAME_MAX      256
#define EFS_PATH_MAX      512

/* Subsystem ids carrying EFS2: standard, and the alternate one used by some
 * Quectel/Foxconn builds. */
#define DIAG_SUBSYS_EFS_STD  0x13
#define DIAG_SUBSYS_EFS_ALT  0x3E

/* Open flags -- POSIX values, not the ARM-ABI ones.  O_CREAT is 0x40; 0x100
 * silently works for overwrites only and fails to create new files. */
#define EFS_O_RDONLY    0x00000
#define EFS_O_WRONLY    0x00001
#define EFS_O_RDWR      0x00002
#define EFS_O_CREAT     0x00040
#define EFS_O_TRUNC     0x00200
#define EFS_O_ITEMFILE  0x40000   /* create as an EFS "item file"        */
#define EFS_O_AUTODIR   0x80000   /* modem creates missing parent dirs   */

#define EFS_MAX_IO      1024      /* per-packet read/write payload       */

typedef struct {
    int32_t entry_type;
    int32_t mode;
    int32_t size;
    int32_t atime, mtime, ctime;
    char    name[EFS_NAME_MAX];
} efs_dirent_t;

typedef struct {
    int32_t mode;
    int32_t size;
    int32_t nlink;
    int32_t atime, mtime, ctime;
} efs_stat_t;

typedef struct {
    diag_t *d;
    uint8_t method;        /* DIAG_SUBSYS_EFS_STD or _ALT */
    int     detected;
    int     timeout_ms;
    int     last_errno;    /* EFS errno reported by the modem */
    char    last_error[256];
} efs_t;

void efs_init(efs_t *e, diag_t *d);
int  efs_detect(efs_t *e);

int  efs_opendir(efs_t *e, const char *path);
int  efs_readdir(efs_t *e, int32_t dirp, uint32_t seqno, efs_dirent_t *ent); /* 0 ok, 1 eof, -1 err */
void efs_closedir(efs_t *e, int32_t dirp);

int  efs_stat(efs_t *e, const char *path, efs_stat_t *st);   /* follows links */
int  efs_lstat(efs_t *e, const char *path, efs_stat_t *st);  /* describes the link */
int  efs_open(efs_t *e, const char *path, int32_t oflag, int32_t mode);
int  efs_read(efs_t *e, int32_t fd, uint32_t nbytes, uint32_t offset,
              uint8_t *buf, size_t bufsz);
int  efs_write(efs_t *e, int32_t fd, uint32_t offset, const uint8_t *data, uint32_t len);
void efs_close_fd(efs_t *e, int32_t fd);

int  efs_mkdir(efs_t *e, const char *path, int16_t mode);
int  efs_mkdirp(efs_t *e, const char *path);   /* creates parents of `path` */
int  efs_rmdir(efs_t *e, const char *path);
int  efs_unlink(efs_t *e, const char *path);
int  efs_chmod(efs_t *e, const char *path, int16_t mode);
int  efs_symlink(efs_t *e, const char *target, const char *linkpath);
int  efs_rename(efs_t *e, const char *from, const char *to);
int  efs_readlink(efs_t *e, const char *path, char *buf, size_t bufsz);

int  efs_get_item(efs_t *e, const char *path, uint8_t *buf, size_t bufsz, int32_t *len);
int  efs_put_item(efs_t *e, const char *path, const uint8_t *data, int32_t len,
                  int32_t flags, int16_t mode);

int  efs_sync(efs_t *e);
int  efs_statfs(efs_t *e, const char *path, uint8_t *raw, size_t rawsz, int *rawlen);

/* Modem-generated tar of an EFS subtree (FS_IMAGE, opcodes 54-56). */
int  efs_image_dump(efs_t *e, const char *efs_path, const char *local_path,
                    uint64_t *bytes_out);

/* Convenience wrappers used by the command layer. */
int  efs_read_file(efs_t *e, const char *path, uint8_t **out, size_t *len);
int  efs_read_file_sized(efs_t *e, const char *path, uint8_t **out, size_t *len,
                         int32_t size_hint);
/* force_item: -1 decide from the path, 0 write a regular file, 1 an item file. */
int  efs_write_file(efs_t *e, const char *path, const uint8_t *data, size_t len,
                    int16_t mode, int force_item);
int  efs_is_item_path(const char *path);

/* NV items (DIAG 0x26/0x27 and the indexed 0x4B/0x30 variants). */
#define NV_ITEM_DATA_SIZE 128
int  nv_read(diag_t *d, uint16_t item, uint8_t *data, uint16_t *status, int timeout_ms);
int  nv_write(diag_t *d, uint16_t item, const uint8_t *data, size_t len,
              uint16_t *status, int timeout_ms);
int  nv_read_sub(diag_t *d, uint16_t item, uint16_t index, uint8_t *data,
                 uint16_t *status, int timeout_ms);
int  nv_write_sub(diag_t *d, uint16_t item, uint16_t index, const uint8_t *data,
                  size_t len, uint16_t *status, int timeout_ms);
const char *nv_status_str(uint16_t status);

/* Sends the 6-digit Service Programming Code (DIAG 0x41).  On the phones seen
 * so far a modem answers NV writes with 0x42 ("security state does not allow
 * it") until this has been accepted.  *ok is set to 1 when the target reports
 * the code correct, 0 when it rejects it.  Returns 0 on a valid answer, -1 on
 * a transport error.  spc must be exactly six ASCII digits. */
int  diag_spc(diag_t *d, const char *spc, int *ok, int timeout_ms);

#endif
