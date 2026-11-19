# bunnylock modem backend

Source: https://github.com/btsearch/bunnylock
Modem library revision: caf2cc7b18e447a0bbbdf851ea77278e4bc94196 (2.1.0)

Vendored `modem/src`, `consumer-rules.pro`, and the app's Shizuku bridge and
associated localized strings. Copyright remains with the bunnylock contributors.
This module is licensed under GPL-3.0; see LICENSE. It is integrated into Network
Helper as a modified component, with Network Helper's own Compose UI.

This sync includes the split backend implementation, Unisoc AT band controls,
MediaTek kept-off band handling, and the combined HAL request for LTE/NR locks.
The vendored Root/Shizuku bridge is retained and adapted to the new command
dispatcher. The upstream standalone screens and Samsung menu launcher remain
outside this integration. Session events, bounded helper stderr and Shizuku
command failures go to the Network Helper diagnostic console.

Local changes: Gradle/SDK/NDK alignment; Shizuku resources and provider integrated
into the library; Android 10 guards for CellIdentity bands; host lifecycle and
selection integration; 16 KB ELF alignment for the QRTR JNI library; awaitable
SIM/backend selection and explicit refresh-failure reporting for the host.
Further local modifications are recorded in git.

Host additions: shared-control presentation adapter support (arbitrary RAT masks),
and root-only descriptor-based QRTR/QMUX owner discovery/release in
`qmi_owners.c` / `BackendCleanup.kt`.

The bunnylock icon is extracted from its launcher foreground. The qcom-band-menu
icon in the host app is extracted from that project's launcher resources. Both
are used to identify and credit their upstream projects.
