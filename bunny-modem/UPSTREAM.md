# bunnylock modem backend

Source: https://github.com/btsearch/bunnylock
Modem library revision: dd1d728ac2718df588fb63f517265e27e118d5d8

Vendored `modem/src`, `consumer-rules.pro`, and the app's Shizuku bridge and
associated localized strings. Copyright remains with the bunnylock contributors.
This module is licensed under GPL-3.0; see LICENSE. It is integrated into Network
Helper as a modified component, with Network Helper's own Compose UI.

This sync includes MediaTek detection and RAT fallback, HAL band locks without a
visible cell, RAT-mask compatibility and the upstream IMS/CA backend support.
The Shizuku bridge picks up the extracted remote shell. The host uses the
privileged helper to request AP disassociation through Android's Wi-Fi service.
The upstream standalone screens and Samsung menu launcher are
outside this integration.

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
