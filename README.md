# 欧加网络助手 / Oplus Network Helper

一款面向 Android 高通/联发科设备的网络与信号分析工具。应用包名为
`com.nvmex.networkhelper`，使用 Jetpack Compose 构建，并包含独立的 VPN/热点功能模块。

An Android network and signal analysis tool for Qualcomm and MediaTek devices.
The application ID is `com.nvmex.networkhelper`. The UI is built with Jetpack
Compose and includes a separate VPN/hotspot module.

## 主要功能 / Features

- **双卡网络面板 / Dual-SIM network panel**：对比 SIM1/SIM2 的运营商、数据网/NR 模式、频段、频点、小区和链路信息。
- **信号质量 / Signal quality**：显示 RSRP、RSRQ、SINR、RSSI、信号等级和评分，并支持历史信号曲线。
- **路测地图 / Drive-test map**：记录定位轨迹和网络打点，支持双卡数据查看与切换。
- **高通 QOS / Qualcomm QOS**：查看全局事件、Paging/Mobility、NAS 状态等调试数据。
- **工程模式工具 / Engineer-mode tools**：访问网络工程模式能力，并提供工程模式文件清理工具。
- **频段与小区 / Bands and cells**：查看和分析 LTE/NR 频段、小区参数、载波聚合等信息。
- **网络工具 / Network utilities**：集成 iPerf 测速、Wi-Fi 信息、Ping 和 VPN/热点管理。
- **Xposed/厂商接口 / Xposed and vendor APIs**：部分高级功能需要 root、Xposed/LSPosed 或特定厂商系统接口。

## 构建环境 / Build requirements

- Android Studio（建议稳定版） / Android Studio (stable recommended)
- JDK 17（Gradle 8.13 不支持 JDK 25） / JDK 17 (Gradle 8.13 does not support JDK 25)
- Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`

在 Android Studio 中打开项目根目录并等待 Gradle 同步：

Open the project root in Android Studio and wait for Gradle sync:

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

## 配置 / Configuration

本地配置、签名文件、私钥和 Firebase 配置不会提交到仓库。
高德地图 Key 通过 Gradle 参数注入：

Local configuration, signing files, private keys and Firebase configuration are
excluded from the repository. Inject the AMap key at build time:

```powershell
.\gradlew.bat assembleDebug -PAMAP_API_KEY=你的高德Key
```

You may also add `AMAP_API_KEY=your_key` to your user-level Gradle properties.
Some features require root access, vendor-specific APIs or a physical target
device and may not work on a standard emulator.

## 项目结构 / Project structure

```text
app/                  主应用模块 / Main application module
vpnhotspot-mobile/    VPN/热点库模块 / VPN and hotspot library module
gradle/               Gradle wrapper and dependency versions
```

## 许可证 / License

本项目原创代码采用 **Apache License 2.0**，详见 [LICENSE](LICENSE)。
仓库还包含采用其他许可证的第三方代码；请阅读
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，并保留相应的版权和许可证声明。

Original code in this project is licensed under the **Apache License 2.0**;
see [LICENSE](LICENSE). The repository also contains third-party code under
other licenses. Read [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and retain
the applicable copyright and license notices.
