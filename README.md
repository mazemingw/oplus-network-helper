# 欧加网络助手（Oplus Network Helper）

[English version](README.en.md)

面向 Android 高通/联发科设备的网络与信号分析工具。应用包名为
`com.nvmex.networkhelper`，使用 Jetpack Compose 构建，并包含独立的 VPN/热点功能模块。

## 版本与安装包

- 当前源码：`v1.5.3`（versionCode `53`）；本次提交源码，不创建 Release。
- [v1.4.5 ARM64 安装包](https://github.com/mazemingw/oplus-network-helper/releases/tag/v1.4.5)
- 架构：`arm64-v8a`
- SHA-256：`624EEC9EA2FE25152A4320CD6C44758B8DA820A38DFDF0CAFDFF58E3609DED04`

## 主要功能

- **双卡网络面板**：对比 SIM1/SIM2 的运营商、数据网/NR 模式、频段、频点、小区和链路信息，标记数据卡并展示小区变化时间线。
- **信号质量**：显示 RSRP、RSRQ、SINR、RSSI、信号等级和评分，并支持历史信号曲线。
- **路测地图**：同一采样时刻保存双卡数据，回读时用 TAB 切换数据组；仅主动恢复 Session 时定位到记录起点。支持 1–10 km 附近基站查询，并独立筛选 4G/5G 制式及 Band。
- **QoS 诊断**：查看高通 DATA/IMS 的 QCI、AMBR、NAS 等数据及按 SIM 保存的缓存；联发科 AT 读取通道为实验功能，尚未真机验证。
- **工程模式工具**：访问网络工程模式能力，并提供工程模式文件清理工具。
- **频段与小区**：查看和分析 LTE/NR 频段、小区参数、载波聚合等信息。
- **网络工具**：集成 iPerf 测速、Wi-Fi 信息、Ping 和 VPN/热点管理；Wi-Fi 信道图支持空闲区间压缩和 80+80 MHz 占用展示。
- **应用更新与插件共享**：更新下载显示下载、校验和安装阶段，检查 APK 包名、版本及签名；插件导入兼容独立插件包与包含 framework 的分享包。
- **Xposed/厂商接口**：部分高级功能需要 root、Xposed/LSPosed 或特定厂商系统接口。

## 路测与附近基站

在路测地图 FAB 中打开 **地图绘制与探测参数**（原“扇区绘制参数”），可设置扇区角度/距离、采样间隔、附近基站探测开关、1–10 km 半径、制式和可选 Band。探测默认关闭，默认半径 3 km；也可点击“立即探测”。查询的是后台工参库中的基站。

制式可选全部、4G 或 5G，Band 留空表示不限频段。数字（如 `3`）筛选所选制式的同号频段；`B3` 限定 4G，`n78` 限定 5G。制式与前缀冲突时会提示修改。频段按工参频点范围匹配，NR 重叠频段可能同时命中。

进入地图时保留当前位置，选择“恢复 Session”后才切换到记录起点。双卡记录分别显示在 SIM TAB 中，旧单卡记录仍可读取。详见 [路测地图说明](docs/drive-test-map.md)。

后台单独维护于 [NetworkHelperBack](https://github.com/mazemingw/NetworkHelperBack)，采用 Apache 2.0；新增接口需要更新后台并重启服务。协议见 [附近基站接口文档](https://github.com/mazemingw/NetworkHelperBack/blob/main/nearby-api.md)。

## 截图

截图按功能模块存放在 [`docs/images`](docs/images) 中，点击图片可查看原图。

### HOME：首页

<p>
  <a href="docs/images/home/home.png"><img src="docs/images/home/home.png" alt="首页" width="260"></a>
  <a href="docs/images/home/control-panel.png"><img src="docs/images/home/control-panel.png" alt="网络控制面板" width="260"></a>
  <a href="docs/images/home/qos-data.png"><img src="docs/images/home/qos-data.png" alt="QOS 数据" width="260"></a>
</p>

### LOCKBAND：锁频

<p>
  <a href="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-43-38_0b59cd5314832a..png"><img src="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-43-38_0b59cd5314832a..png" alt="锁频设置" width="260"></a>
  <a href="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-54-70_0b59cd5314832a..png"><img src="docs/images/LOCKBAND/Screenshot_2026-09-12-04-56-54-70_0b59cd5314832a..png" alt="锁频详情" width="260"></a>
</p>

### RADAR：雷达

<p>
  <a href="docs/images/radar/Screenshot_2026-09-12-04-37-58-73_0b59cd5314832a..png"><img src="docs/images/radar/Screenshot_2026-09-12-04-37-58-73_0b59cd5314832a..png" alt="信号雷达" width="260"></a>
</p>

### WIFI：Wi-Fi

<p>
  <a href="docs/images/WIFI/Screenshot_2026-09-12-04-38-19-73_0b59cd5314832a..png"><img src="docs/images/WIFI/Screenshot_2026-09-12-04-38-19-73_0b59cd5314832a..png" alt="Wi-Fi 概览" width="260"></a>
  <a href="docs/images/WIFI/Screenshot_2026-09-12-04-38-32-71_0b59cd5314832a..png"><img src="docs/images/WIFI/Screenshot_2026-09-12-04-38-32-71_0b59cd5314832a..png" alt="Wi-Fi 详情" width="260"></a>
</p>

### HOTSPOT：热点

<p>
  <a href="docs/images/hotspot/Screenshot_2026-09-12-04-38-57-83_0b59cd5314832a..png"><img src="docs/images/hotspot/Screenshot_2026-09-12-04-38-57-83_0b59cd5314832a..png" alt="VPN 热点" width="260"></a>
</p>

### MENU：菜单

<p>
  <a href="docs/images/menu/Screenshot_2026-09-12-04-39-08-14_0b59cd5314832a..png"><img src="docs/images/menu/Screenshot_2026-09-12-04-39-08-14_0b59cd5314832a..png" alt="菜单" width="260"></a>
  <a href="docs/images/menu/Screenshot_2026-09-12-04-39-11-61_0b59cd5314832a..png"><img src="docs/images/menu/Screenshot_2026-09-12-04-39-11-61_0b59cd5314832a..png" alt="菜单工具" width="260"></a>
</p>

### SETTINGS：设置

<p>
  <a href="docs/images/settings/Screenshot_2026-09-12-04-39-27-05_0b59cd5314832a..png"><img src="docs/images/settings/Screenshot_2026-09-12-04-39-27-05_0b59cd5314832a..png" alt="设置" width="260"></a>
  <a href="docs/images/settings/Screenshot_2026-09-12-04-39-31-15_0b59cd5314832a..png"><img src="docs/images/settings/Screenshot_2026-09-12-04-39-31-15_0b59cd5314832a..png" alt="设置详情" width="260"></a>
</p>

## 构建环境

- Android Studio（建议使用稳定版）
- JDK 17（Gradle 8.13 不支持 JDK 25）
- Android SDK 36
- Android NDK `26.3.11579264`
- CMake `3.22.1`

在 Android Studio 中打开项目根目录并等待 Gradle 同步：

```bash
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
.\gradlew.bat assembleDebug
```

本地单元测试与 Debug 构建（无需连接设备）：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

兼容修复的既有验证记录见 [2026-09-18 说明](docs/2026-09-18-compatibility-verification.md)；本次路测检查与手工测试步骤见 [路测地图说明](docs/drive-test-map.md)。

## 配置说明

本地配置文件 `local.properties`、签名文件、私钥和 Firebase 配置不会提交到仓库。
将高德地图 Key 写入项目根目录的 `local.properties`，之后普通编译和 Release 编译都会自动读取：

```properties
AMAP_API_KEY=你的高德Key
```

也可以在 Gradle 命令行使用 `-PAMAP_API_KEY=...` 临时覆盖本地值。注意：Key 会被打包进 APK；请在高德开放平台限制 Key 的 Android 包名/签名 SHA-1，并避免提交 `local.properties`。部分功能需要 root、厂商接口或实体设备，在普通模拟器上可能不可用。

## 项目结构

```text
app/                  主应用模块
vpnhotspot-mobile/    VPN/热点库模块
gradle/               Gradle wrapper 与依赖版本
docs/                 功能说明、验证记录与截图
```

## 许可证

本项目原创代码采用 **Apache License 2.0**，详见 [LICENSE](LICENSE)。仓库还包含采用其他许可证的第三方代码；请阅读 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，并保留相应的版权和许可证声明。
