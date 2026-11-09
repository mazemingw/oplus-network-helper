# 第三方代码与许可证 / Third-party notices

本文件说明仓库中可识别的主要第三方代码。第三方代码的原版权和许可证
优先于本项目的整体许可证；重新分发时请同时提供相应许可证文本。

This file lists the major third-party components identified in this repository.
Their original copyright and license terms remain applicable and take
precedence over the project-wide license for those components.

## VPN Hotspot

`vpnhotspot-mobile/` 是 [Mygod/VPNHotspot](https://github.com/Mygod/VPNHotspot)
的集成/修改版本。上游项目采用 Apache License 2.0，许可证原文见
[VPNHotspot LICENSE](https://github.com/Mygod/VPNHotspot/blob/master/LICENSE)。

`vpnhotspot-mobile/` integrates and modifies code from
[Mygod/VPNHotspot](https://github.com/Mygod/VPNHotspot), which is licensed under
the Apache License 2.0. See the [upstream license](https://github.com/Mygod/VPNHotspot/blob/master/LICENSE).

## iPerf and bundled C sources

`app/src/main/cpp/iperf/` 包含 iPerf 及其依赖的 C 源码。iPerf 采用 BSD
风格许可证，并包含 cJSON、NetBSD、MIT/Lucent 等来源的代码；相关版权和
许可证声明保留在源文件头部。完整的上游许可证见
[esnet/iperf LICENSE](https://github.com/esnet/iperf/blob/master/LICENSE)。

`app/src/main/cpp/iperf/` contains iPerf and bundled C sources. iPerf uses a
BSD-style license and includes code from cJSON, NetBSD, MIT/Lucent and other
contributors. Copyright and license notices are retained in the source headers.
See the [upstream iPerf license](https://github.com/esnet/iperf/blob/master/LICENSE).

## AndroidX / AOSP-derived resources

部分布局和资源来自 Android Open Source Project/AndroidX，并在文件头部
标注 Apache License 2.0 版权声明。详情请以对应源文件中的声明和
[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 为准。

Some layouts and resources are derived from Android Open Source Project/AndroidX
and carry Apache License 2.0 notices in their file headers. Refer to each
source header and the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Qualcomm QMI helper

`app/src/main/cpp/qmi/nh-qmi.c` is adapted from
[Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu), commit
`bff702b5b4916f68feb3bf9308b91be56846f2e6`, with contributions from h3nnes.
It is a separate root executable, licensed under GPL-3.0; its license is retained
at `app/src/main/cpp/qmi/LICENSE-GPL-3.0` and packaged in `assets/qmi/COPYING`.
The corresponding modified source is included in this repository. Build steps
and changes are documented in `docs/qmi-controls.md`. The app uses its local
NDJSON interface; the UI remains native to Network Helper.
`util/qmi/QmiRadioSnapshot.kt` adapts NAS neighbor, LTE CA and NR frequency
message layouts and decoding from upstream `network/NasDecode.kt` at the same
commit, under GPL-3.0. It retains only reported measurements and uses Network
Helper's own models, validation, source selection and presentation.
Upstream changes through `e924f388b8c69dfbe7f91e76a7721698d8099e5a` were reviewed
on 2026-11-06. The app-side QMI integration includes NR-mode rejection,
partial success, and writing a changed RAT before restoring band masks.
See `app/src/main/cpp/qmi/UPSTREAM.md` for scope.

## bunnylock modem backend and Shizuku bridge

`bunny-modem/` includes the modem library and the Root/Shizuku bridge from
[btsearch/bunnylock](https://github.com/btsearch/bunnylock), revision
`dd1d728ac2718df588fb63f517265e27e118d5d8` for the modem library. Copyright remains with the bunnylock
authors and contributors. Licensed under GPL-3.0; the complete license and
modified source are included in `bunny-modem/`. The combined app containing this
module is distributed under GPL-3.0 terms, retaining existing compatible license
notices. Source provenance and local adaptations: `bunny-modem/UPSTREAM.md`.

`driver_bunnylock.xml` is extracted from bunnylock's launcher foreground;
`driver_qcom_bandmenu.png` is extracted from qcom-band-menu's launcher resources.
They identify and credit the respective upstream backends. Our thanks to both
projects and their contributors.

## Qualcomm EFS explorer module

`qcom-efs/` vendors [catxeon/qcom-efs-browser](https://github.com/catxeon/qcom-efs-browser),
upstream revision `b7fa178fbceb294e5f3e79cadcbf9a9f961670e4` (1.4.8), with the local
Chinese, verified-backup and restoration enhancements. Copyright (c) 2026 catxeon.
The upstream BSD-3-Clause license and NOTICE are retained in the module and included
in APK resources under `META-INF/qcom-efs/`. Native helper source and build scripts
are included; this executable uses an independent app-UID socket (`nh_efsd`).
The host app's existing GPL distribution terms remain applicable to the combined app.

## Other dependencies

Gradle/Maven dependencies retain the licenses chosen by their respective
authors. Consult the dependency metadata and Android Studio's dependency
inspection tools before redistributing a binary package.
