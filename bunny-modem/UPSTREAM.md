# bunnylock modem backend

Source: https://github.com/btsearch/bunnylock
Revision: 55f47a87344fd7a42f5d903c660776b334664556 (2026-10-27)

Vendored `modem/src`, `consumer-rules.pro`, and the app's Shizuku bridge and
associated localized strings. Copyright remains with the bunnylock contributors.
This module is licensed under GPL-3.0; see LICENSE. It is integrated into Network
Helper as a modified component, with Network Helper's own Compose UI.

Local changes: Gradle/SDK/NDK alignment; Shizuku resources and provider integrated
into the library; Android 10 guards for CellIdentity bands; host lifecycle and
selection integration; 16 KB ELF alignment for the QRTR JNI library; awaitable
SIM/backend selection and explicit refresh-failure reporting for the host.
Further local modifications are recorded in git.

Host additions: shared-control presentation adapter support (arbitrary RAT masks),
and root-only descriptor-based QRTR/QMUX owner discovery/release in
`qmi_owners.c` / `BackendCleanup.kt`. No upstream modem lock wire layouts changed.

The bunnylock icon is extracted from its launcher foreground. The qcom-band-menu
icon in the host app is extracted from that project's launcher resources. Both
are used to identify and credit their upstream projects.
