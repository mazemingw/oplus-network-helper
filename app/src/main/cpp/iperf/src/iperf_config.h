#pragma once

// Android / Bionic baseline
#define __ANDROID__ 1

// Common C headers / functions
#define HAVE_ARPA_INET_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_NETDB_H 1
#define HAVE_NETINET_IN_H 1
#define HAVE_NETINET_TCP_H 1
#define HAVE_PTHREAD_H 1
#define HAVE_SIGNAL_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_SELECT_H 1
#define HAVE_SYS_SOCKET_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_UIO_H 1
#define HAVE_UNISTD_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_STDATOMIC_H 1
#define HAVE_ATOMIC 1

// Threading / time
#define HAVE_PTHREAD 1
#define HAVE_CLOCK_GETTIME 1

// Socket features typically available on Android
#define HAVE_SETSOCKOPT 1
#define HAVE_GETTIMEOFDAY 1

// Disable features that often break on Android unless you really need them
// (We can re-enable later if required)
#undef HAVE_SCTP
#undef HAVE_ZLIB
#undef HAVE_SSL
#undef HAVE_CPUSET
#undef HAVE_AFFINITY
// Autotools package metadata (used by iperf_locale.c)
#ifndef PACKAGE_NAME
#define PACKAGE_NAME "iperf"
#endif

#ifndef PACKAGE_TARNAME
#define PACKAGE_TARNAME "iperf"
#endif

#ifndef PACKAGE_VERSION
#define PACKAGE_VERSION IPERF_VERSION
#endif

#ifndef PACKAGE_STRING
#define PACKAGE_STRING PACKAGE_NAME " " PACKAGE_VERSION
#endif
