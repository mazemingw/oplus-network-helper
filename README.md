# 欧加网络助手（Oplus Network Helper）

面向 Android 高通/联发科设备的网络与信号分析工具，应用包名为
`com.nvmex.networkhelper`。项目使用 Jetpack Compose 构建界面，并集成了双卡网络信息、信号指标、工程模式、路测记录和 VPN 热点等功能模块。

## 主要功能

- 查看 SIM1/SIM2 的运营商、数据网络/NR 模式、频段、频点和小区参数
- 展示 RSRP、RSRQ、SINR、RSSI 等信号指标及信号评分
- 高通平台 QOS 与全局事件数据查看
- 路测地图记录网络轨迹和信号点位
- 工程模式相关工具与 VPN/热点功能

## 构建环境

- Android Studio（建议使用稳定版）
- JDK 17（项目 Gradle/Android 配置使用 Java 17；JDK 25 不兼容 Gradle 8.13）
- Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`

在 Android Studio 中打开项目根目录，等待 Gradle 同步完成后即可运行：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

## 配置说明

`local.properties`、签名文件、私钥、Firebase 配置等本机文件不会提交到仓库。高德地图 Key 通过 Gradle 参数注入，不会写入源码；构建时可使用：

```powershell
.\gradlew.bat assembleDebug -PAMAP_API_KEY=你的高德Key
```

也可以将 `AMAP_API_KEY=你的高德Key` 写入用户级 Gradle 配置。使用其他需要密钥的功能时，请按相关 SDK 的要求配置对应凭据。

应用涉及部分需要 root、厂商接口或特定设备环境的能力；在普通模拟器或非目标设备上，相关功能可能不可用。

## 项目结构

```text
app/                  主应用模块
vpnhotspot-mobile/    VPN/热点功能模块
gradle/               Gradle 版本目录与依赖版本
```

## 开源许可

当前项目尚未声明正式开源许可证。除非另有说明，仓库内容不授予默认的再分发或商业使用许可。
