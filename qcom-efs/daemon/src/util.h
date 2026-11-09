/* util.h -- tiny string builder, JSON accessors, base64/hex, logging. */
#ifndef QEFS_UTIL_H
#define QEFS_UTIL_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    char  *p;
    size_t len;
    size_t cap;
} sbuf;

void sb_init(sbuf *s);
void sb_free(sbuf *s);
void sb_reset(sbuf *s);
void sb_raw(sbuf *s, const char *data, size_t n);
void sb_str(sbuf *s, const char *z);
void sb_fmt(sbuf *s, const char *fmt, ...);
/* Appends a fully quoted, escaped JSON string. */
void sb_json_str(sbuf *s, const char *z);

/* Flat-object accessors. The input is produced by our own client, so a
   forgiving scanner is enough; nested objects are not searched. */
int json_get_str(const char *obj, const char *key, char *out, size_t outsz);
int json_get_i64(const char *obj, const char *key, long long *out);
int json_get_bool(const char *obj, const char *key, int *out);
int json_has(const char *obj, const char *key);

size_t b64_encode(const uint8_t *in, size_t n, char *out);
long   b64_decode(const char *in, uint8_t *out, size_t outmax);
size_t hex_encode(const uint8_t *in, size_t n, char *out);
long   hex_decode(const char *in, uint8_t *out, size_t outmax);

extern int g_verbose;
void qlog(const char *fmt, ...);

#endif
