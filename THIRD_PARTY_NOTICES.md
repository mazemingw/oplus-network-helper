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

## Other dependencies

Gradle/Maven dependencies retain the licenses chosen by their respective
authors. Consult the dependency metadata and Android Studio's dependency
inspection tools before redistributing a binary package.
