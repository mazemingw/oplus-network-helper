#include "util.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>
#include <time.h>

int g_verbose = 0;

void qlog(const char *fmt, ...)
{
    if (!g_verbose) return;
    static char last[1536];
    static time_t last_at;
    static unsigned repeated;
    char line[1536];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    flockfile(stderr);
    time_t now = time(NULL);
    if (!strcmp(last, line) && now - last_at < 10) {
        if (repeated < 1000000) repeated++;
        funlockfile(stderr);
        return;
    }
    fflush(stderr);
    struct stat info;
    if (fstat(STDERR_FILENO, &info) == 0 && S_ISREG(info.st_mode) && info.st_size >= 1024 * 1024) {
        /* Only truncate the already-open app log, never any modem/EFS path. */
        if (ftruncate(STDERR_FILENO, 0) == 0) lseek(STDERR_FILENO, 0, SEEK_SET);
    }
    if (repeated) fprintf(stderr, "qcom-efsd: previous line repeated %u times\n", repeated);
    repeated = 0;
    snprintf(last, sizeof(last), "%s", line);
    last_at = now;
    fprintf(stderr, "qcom-efsd: %s\n", line);
    funlockfile(stderr);
}

void sb_init(sbuf *s) { s->p = NULL; s->len = 0; s->cap = 0; }
void sb_free(sbuf *s) { free(s->p); sb_init(s); }
void sb_reset(sbuf *s) { s->len = 0; if (s->p) s->p[0] = 0; }

static void sb_grow(sbuf *s, size_t need)
{
    if (s->len + need + 1 <= s->cap) return;
    size_t cap = s->cap ? s->cap : 256;
    while (cap < s->len + need + 1) cap *= 2;
    char *np = realloc(s->p, cap);
    if (!np) { fprintf(stderr, "qcom-efsd: out of memory\n"); _exit(3); }
    s->p = np;
    s->cap = cap;
}

void sb_raw(sbuf *s, const char *data, size_t n)
{
    sb_grow(s, n);
    memcpy(s->p + s->len, data, n);
    s->len += n;
    s->p[s->len] = 0;
}

void sb_str(sbuf *s, const char *z) { sb_raw(s, z, strlen(z)); }

void sb_fmt(sbuf *s, const char *fmt, ...)
{
    char tmp[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n < 0) return;
    if ((size_t)n < sizeof tmp) { sb_raw(s, tmp, (size_t)n); return; }

    char *big = malloc((size_t)n + 1);
    if (!big) return;
    va_start(ap, fmt);
    vsnprintf(big, (size_t)n + 1, fmt, ap);
    va_end(ap);
    sb_raw(s, big, (size_t)n);
    free(big);
}

void sb_json_str(sbuf *s, const char *z)
{
    sb_raw(s, "\"", 1);
    for (const unsigned char *q = (const unsigned char *)z; *q; q++) {
        switch (*q) {
        case '"':  sb_str(s, "\\\""); break;
        case '\\': sb_str(s, "\\\\"); break;
        case '\n': sb_str(s, "\\n");  break;
        case '\r': sb_str(s, "\\r");  break;
        case '\t': sb_str(s, "\\t");  break;
        default:
            if (*q < 0x20) sb_fmt(s, "\\u%04x", *q);
            else sb_raw(s, (const char *)q, 1);
        }
    }
    sb_raw(s, "\"", 1);
}

/* ---- JSON ------------------------------------------------------------- */

/* Returns a pointer just past `"key":` at nesting depth 1, or NULL. */
static const char *json_find(const char *obj, const char *key)
{
    size_t klen = strlen(key);
    int depth = 0, in_str = 0;

    for (const char *p = obj; *p; p++) {
        if (in_str) {
            if (*p == '\\' && p[1]) p++;
            else if (*p == '"') in_str = 0;
            continue;
        }
        if (*p == '"') {
            if (depth == 1 && strncmp(p + 1, key, klen) == 0 && p[1 + klen] == '"') {
                const char *q = p + 2 + klen;
                while (*q == ' ' || *q == '\t') q++;
                if (*q == ':') {
                    q++;
                    while (*q == ' ' || *q == '\t') q++;
                    return q;
                }
            }
            in_str = 1;
            continue;
        }
        if (*p == '{' || *p == '[') depth++;
        else if (*p == '}' || *p == ']') depth--;
    }
    return NULL;
}

int json_has(const char *obj, const char *key) { return json_find(obj, key) != NULL; }

static int hexval(int c)
{
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int json_get_str(const char *obj, const char *key, char *out, size_t outsz)
{
    const char *p = json_find(obj, key);
    if (!p || *p != '"' || outsz == 0) return -1;
    p++;

    size_t n = 0;
    while (*p && *p != '"') {
        int c = (unsigned char)*p++;
        if (c == '\\') {
            int e = (unsigned char)*p++;
            switch (e) {
            case 'n': c = '\n'; break;
            case 'r': c = '\r'; break;
            case 't': c = '\t'; break;
            case 'b': c = '\b'; break;
            case 'f': c = '\f'; break;
            case 'u': {
                int v = 0;
                for (int i = 0; i < 4; i++) {
                    int h = hexval((unsigned char)*p++);
                    if (h < 0) return -1;
                    v = v * 16 + h;
                }
                /* EFS paths are ASCII in practice; encode anything else as UTF-8. */
                if (v < 0x80) c = v;
                else if (v < 0x800) {
                    if (n + 2 >= outsz) return -1;
                    out[n++] = (char)(0xC0 | (v >> 6));
                    c = 0x80 | (v & 0x3F);
                } else {
                    if (n + 3 >= outsz) return -1;
                    out[n++] = (char)(0xE0 | (v >> 12));
                    out[n++] = (char)(0x80 | ((v >> 6) & 0x3F));
                    c = 0x80 | (v & 0x3F);
                }
                break;
            }
            default: c = e;
            }
        }
        if (n + 1 >= outsz) return -1;
        out[n++] = (char)c;
    }
    if (*p != '"') return -1;
    out[n] = 0;
    return (int)n;
}

int json_get_i64(const char *obj, const char *key, long long *out)
{
    const char *p = json_find(obj, key);
    if (!p) return -1;
    if (*p == '"') p++;                       /* tolerate "123" */
    char *end = NULL;
    long long v = strtoll(p, &end, 0);        /* 0x.. accepted too */
    if (end == p) return -1;
    *out = v;
    return 0;
}

int json_get_bool(const char *obj, const char *key, int *out)
{
    const char *p = json_find(obj, key);
    if (!p) return -1;
    if (strncmp(p, "true", 4) == 0)  { *out = 1; return 0; }
    if (strncmp(p, "false", 5) == 0) { *out = 0; return 0; }
    if (*p == '1') { *out = 1; return 0; }
    if (*p == '0') { *out = 0; return 0; }
    return -1;
}

/* ---- base64 / hex ----------------------------------------------------- */

static const char B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

size_t b64_encode(const uint8_t *in, size_t n, char *out)
{
    size_t o = 0;
    for (size_t i = 0; i < n; i += 3) {
        unsigned v = (unsigned)in[i] << 16;
        if (i + 1 < n) v |= (unsigned)in[i + 1] << 8;
        if (i + 2 < n) v |= in[i + 2];
        out[o++] = B64[(v >> 18) & 63];
        out[o++] = B64[(v >> 12) & 63];
        out[o++] = (i + 1 < n) ? B64[(v >> 6) & 63] : '=';
        out[o++] = (i + 2 < n) ? B64[v & 63] : '=';
    }
    out[o] = 0;
    return o;
}

long b64_decode(const char *in, uint8_t *out, size_t outmax)
{
    int8_t rev[256];
    memset(rev, -1, sizeof rev);
    for (int i = 0; i < 64; i++) rev[(unsigned char)B64[i]] = (int8_t)i;

    unsigned acc = 0;
    int bits = 0;
    size_t o = 0;
    for (const unsigned char *p = (const unsigned char *)in; *p; p++) {
        if (*p == '=') break;
        if (*p == '\n' || *p == '\r' || *p == ' ') continue;
        int v = rev[*p];
        if (v < 0) return -1;
        acc = (acc << 6) | (unsigned)v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            if (o >= outmax) return -1;
            out[o++] = (uint8_t)((acc >> bits) & 0xFF);
        }
    }
    return (long)o;
}

size_t hex_encode(const uint8_t *in, size_t n, char *out)
{
    static const char H[] = "0123456789abcdef";
    size_t o = 0;
    for (size_t i = 0; i < n; i++) {
        out[o++] = H[in[i] >> 4];
        out[o++] = H[in[i] & 15];
    }
    out[o] = 0;
    return o;
}

long hex_decode(const char *in, uint8_t *out, size_t outmax)
{
    size_t o = 0;
    int hi = -1;
    for (const char *p = in; *p; p++) {
        if (*p == ' ' || *p == ':' || *p == '\n' || *p == '\r') continue;
        int v = hexval((unsigned char)*p);
        if (v < 0) return -1;
        if (hi < 0) { hi = v; continue; }
        if (o >= outmax) return -1;
        out[o++] = (uint8_t)((hi << 4) | v);
        hi = -1;
    }
    if (hi >= 0) return -1;
    return (long)o;
}
