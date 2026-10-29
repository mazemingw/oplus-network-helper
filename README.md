# 欧加网络助手（Oplus Network Helper）

[English version](README.en.md)

面向 Android 高通/联发科设备的网络与信号分析工具。应用包名为
`com.nvmex.networkhelper`，使用 Jetpack Compose 构建，并包含独立的 VPN/热点功能模块。

## 版本

- 当前源码版本：`v1.6.1`（versionCode `61`）。
- 架构：`arm64-v8a`

## 主要功能

- **双卡网络面板**：对比 SIM1/SIM2 的运营商、数据网/NR 模式、频段、频点、小区和链路信息；基站时域图显示频点和 RSRP，网络面板内提供默认折叠、跨卡记忆展开状态的多天线 RX 测量。
- **信号质量**：显示 RSRP、RSRQ、SINR、RSSI、信号等级和评分，并支持历史信号曲线。
- **路测地图**：同一采样时刻保存双卡数据，回读时用 TAB 切换数据组；支持隐藏基站三角标，回读默认全览，可选择起点视角。支持 1–10 km 附近基站查询，并独立筛选 4G/5G 制式及 Band。
- **QoS 诊断**：查看高通 DATA/IMS 的 QCI、AMBR、NAS 等数据及按 SIM 保存的缓存；联发科 AT 读取通道为实验功能，尚未真机验证。
- **工程模式工具**：访问网络工程模式能力，并提供工程模式文件清理工具。
- **频段与小区控制**：Root 高通设备通过 QMI 读取硬件支持频段、设置网络制式和频段、锁定或解除小区；提供运营商频段过滤、当前驻留高亮及锁定确认提示，保留可选的旧式锁频。
- **本地数据管理**：管理导入工参与本地随行随录数据；首页日志、路测记录提供独立管理页及兼容 Excel 的 CSV 导入导出，路测点同时保存原始坐标与 GCJ02 坐标。详见 [数据管理说明](docs/local-data-management.md)。
- **网络工具**：集成 iPerf 测速、Wi-Fi 信息、Ping 和 VPN/热点管理；Wi-Fi 信道图支持空闲区间压缩和 80+80 MHz 占用展示。
- **应用更新与插件共享**：更新下载显示下载、校验和安装阶段，检查 APK 包名、版本及签名；插件导入兼容独立插件包与包含 framework 的分享包。
- **Xposed/厂商接口**：部分高级功能需要 root、Xposed/LSPosed 或特定厂商系统接口。

## 频段、小区与 RX 测量

QMI 集成参照 [Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu)，已审查同步至 [d199690](https://github.com/Fronsipswu/qcom-band-menu/commit/d199690ec5a052553302ee4b4b460d681cbab1eb)。本次上游没有修改原生核心及 NAS 布局，移植了 5G 模式仅在变化时写入、明确拒绝后只读，以及黄色部分成功提示。界面使用本项目的 Material Compose 组件。

- 频段草稿默认不勾选，按 NR SA、NR NSA、LTE、WCDMA、GSM 排列；制式决定相应卡片是否显示。频段按钮附频率或 SUL 标记，选中状态与当前驻留分别标识。“中国大陆模式”默认开启，按当前 SIM 注册运营商过滤可选频段。
- 小区候选按邻区、近期驻留、LTE CA、当前服务排序，只保留有效 PCI 和完整参数；展开时冻结列表，显示 RSRP 短条及“当前”标记。NRCA 载波频点不作为小区锁定候选。
- 首页与控制页共享各 SIM 的服务小区数据。锁定后核对基带读回及新的驻留采样，信息不足时提示未确认；解除小区锁定按实际活动锁定类型处理。锁小区时可选择同时限制制式，解除时恢复关联前设置。
- 点击首页基站时域图记录，可确认快速锁小区或锁频段，携带实际 SIM 卡槽与订阅信息进入控制页。校验硬件与运营商规则后预填参数或选中频段，仍需手动点击“锁定”；FDD/TDD 默认 SCS 分别为 15/30 kHz，可继续手动调整。控制页按钮统一为矩形，小区类型与 SCS 选中时显示实色和勾选图标，锁定读回面板采用紧凑布局。
- 浮动“锁定 / 解锁”按钮上方显示自动消失的状态提示。“重置通道”提供有界的会话恢复；遇到确认的 QMI 5 绑定冲突时，可停止经过身份验证的原作者应用后台，释放其客户端，不清除频段或小区锁。
- 首页“可锁频段”继续打开运营商频段说明，优先使用真实硬件能力，获取失败才回退到内置列表；N3、N8 标为“不常见”。设置中的“旧式锁频”默认关闭，MTK 用户可尝试开启。
- LTE CA 仅由 QMI 提供；NRCA 优先使用现有工程模式链路，降级到 QMI SCELL 频点，再无信息则留空。RX 测量嵌入网络面板，每条接收链紧凑排列，信号参数使用彩色进度条，PHASE 使用平滑动画的 24dp 填充饼图。
- NRCA 标签展示转换后的频段，原始频点留在详情。时域图优先展示带有效 PCI 的邻区，否则回退驻留历史；邻区快照至少保持 3 秒，方便点击锁定。RX 固定接收链位置，并在同一 SIM 本次展开期间保留出现过的制式分组，缺失读数显示“未报告”，避免 2/4 天线或 LTE/NR 组变化使面板反复缩放。RX、CA 和邻区共用只读采集会话，暂时绑定失败时最多重连两次。

详细数据来源、恢复边界、构建与真机验收要求见 [QMI 控制说明](docs/qmi-controls.md)；上游同步范围与许可证见 [同步记录](app/src/main/cpp/qmi/UPSTREAM.md) 和 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 路测与附近基站

在路测地图 FAB 中打开 **地图绘制与探测参数**（原“扇区绘制参数”），可设置扇区角度/距离、采样间隔、附近基站探测开关、1–10 km 半径、制式和可选 Band。探测默认关闭，默认半径 3 km；也可点击“立即探测”。查询的是后台工参库中的基站。

制式可选全部、4G 或 5G，Band 留空表示不限频段。数字（如 `3`）筛选所选制式的同号频段；`B3` 限定 4G，`n78` 限定 5G。制式与前缀冲突时会提示修改。频段按工参频点范围匹配，NR 重叠频段可能同时命中。

进入地图时保留当前位置。在“路测记录”管理页选择“回读”后，默认全览当前 SIM 和频段筛选后的轨迹；“更多 → 回读初始视角”可保存全览或起点偏好，也可即时选择“轨迹全览 / 回到起点”。路测及回读均可关闭基站三角标。双卡记录分别显示在 SIM TAB 中，旧单卡记录仍可读取。详见 [路测地图说明](docs/drive-test-map.md)。

地图支持密集打点与完整连续轨迹、切换点/服务站天线面开关、服务站红线与距离、运营商滚动名称，以及独立的地图模式与 2D/3D 小按钮。Session 按起点城市命名并估算长度，支持退出自动保存和选中后继续录制。右侧显示 GPS 速度；打开地图后自动融合 GPS、加速度计与陀螺仪，在行驶中校准，失锁后可估算弯道与加减速，默认最长 10 分钟、可配置 1–30 分钟，估算点保留漂移指示，仍需实车验证。

高德定位及打点优先运行，惯导只在定位中断时辅助，不等待惯导就绪。速度面板仅显示速度和惯导“就绪／未就绪”。附近基站仅显示标记，扇区只显示当前服务基站的；回读时只展开当前打点对应的服务站。录制时连接过的基站及其扇区保存到 Session，支持按 SIM 离线回读；基站和打点信息窗追加 MHz 频率。双卡信号对比支持上下滑动并显示小区类型，NR 520830 标为“地铁专网”，原有高铁专网规则保留。

后台单独维护于 [NetworkHelperBack](https://github.com/mazemingw/NetworkHelperBack)，采用 Apache 2.0；新增接口需要更新后台并重启服务。协议见 [附近基站接口文档](https://github.com/mazemingw/NetworkHelperBack/blob/main/nearby-api.md)。

可选的“猎鹰轨迹纠偏”默认关闭，使用高德 Android 猎鹰 SDK 上报并查询驾车道路绑路结果，无须导入线路；采集随路测打点开始、停止，仅浏览地图不上传轨迹。原始定位、惯性估算及双卡路测记录独立保留；当前不提供铁路或地铁纠偏。地图采用半透明浮层，右上显示地图/视角/速度，右下 FAB 与 RSRP 图例对齐，底部中央提供缩放。服务基站显示绿色，选中标记高亮，信息气泡支持点按复制；打点弹窗及 CSV 支持 RSRQ、SINR 与 NR SS 指标。详见 [接入与真机验收说明](docs/map-matching-mvp.md)。

在“更多”FAB 的“频段筛选与覆盖率”中分别输入 4G/5G 频段，例如 `3,41` 和 `41,28`，统一筛选实时打点与历史回读。浮层显示具体筛选频段及其有效路测距离覆盖率；未匹配的路测路径用带深色描边的亮紫色线保留，完整原始数据继续保存。新机 NRCA 带宽解析按系统能力兼容，保留旧系统规则，详见 [分析记录](docs/nrca-pyb110-analysis.md)。

## 截图

截图按功能模块存放在 [`docs/images`](docs/images) 中，点击图片可查看原图。

### HOME：首页

<p>
  <a href="docs/images/home/home.png"><img src="docs/images/home/home.png" alt="首页" width="260"></a>
  <a href="docs/images/home/control-panel.png"><img src="docs/images/home/control-panel.png" alt="网络控制面板" width="260"></a>
  <a href="docs/images/home/qos-data.png"><img src="docs/images/home/qos-data.png" alt="QOS 数据" width="260"></a>
</p>

### LOCKBAND：锁频

以下截图为旧式锁频界面，新版 QMI 界面与功能见上方说明。

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

2026-10-26 验证：v1.6.0 Debug APK 构建成功；单元测试共 380 项，377 项通过、3 项因缺少本地真实样本跳过、0 失败。QMI 原生会话和恢复脚本另有独立模拟测试，命令见 [QMI 控制说明](docs/qmi-controls.md)。本轮仅保留本地测试包，未覆盖安装；快捷导航、双卡写入、锁定及实际驻留效果仍需真机验收。

兼容修复的既有验证记录见 [2026-09-18 说明](docs/2026-09-18-compatibility-verification.md)；路测检查与手工测试步骤见 [路测地图说明](docs/drive-test-map.md)。

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
bunny-modem/          bunnylock 后端与 Root/Shizuku 连接桥（GPL-3.0）
vpnhotspot-mobile/    VPN/热点库模块
gradle/               Gradle wrapper 与依赖版本
docs/                 功能说明、验证记录与截图
```

## 多后端驱动与开源致谢

“频段与小区控制”增加 **驱动&设置** Tab，可选择原有高通 QMI，或 bunnylock
提供的自动识别、高通 QMI、联发科 AT、Pixel、Android HAL 后端。页面包含重启移动
网络、重启基带、清理通道、AT 控制台、设备信息和诊断日志，继续使用欧加助手自己的 UI。

无 Root 路径需要安装、启动并授权 **Shizuku**。ADB 权限支持 Android HAL，以及设备
实际提供的联发科 RIL AT 透传；并不代表所有手机都支持 PCI 锁定或直接访问高通 QMI。
驱动切换会释放当前会话，保留已有锁定。“清理通道”会释放自己的服务；Root 可用时
还会按包名及进程归属检查 qcom-band-menu 和 bunnylock 的后台，只清理确认身份的实例，
不发送解锁命令、不重启系统 radio 服务。Root 下还会扫描实际 QRTR/QMUX 占用并校验
第三方进程归属，不局限于这两个包名；清理后自动重连并刷新当前驱动。受内核或权限
限制而无法检查的对象会单独提示。两类驱动复用同一个频段与小区控制 UI，按能力显示组件。

诚挚感谢 [Fronsipswu/qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu)
与 [btsearch/bunnylock](https://github.com/btsearch/bunnylock) 的作者及所有贡献者。
感谢你们开放协议实现、后端和兼容经验，让欧加网络助手能够服务更多设备。
页面和驱动列表使用两项目原图标标识来源，并提供 GitHub 链接。

接入范围、来源版本和验证步骤见 [多后端驱动说明](docs/modem-backends.md)。

清理后读回仍失败时，可在弹窗查看已知系统 NAS 使用组件的作用、状态与暂停影响，逐项暂停并验证，
也可手动恢复。“驱动&设置”中的“替我决定”默认开启（预选后仍需确认），“自动恢复”默认关闭；
开启后退出应用会恢复助手暂停前正在运行的组件，保留原先已停止的状态。
详见 [系统通道恢复说明](docs/qmi-system-recovery.md)，组件身份与占用数量不能推广为所有固件的固定值。

## 许可证

本项目原创代码采用 **Apache License 2.0**，详见 [LICENSE](LICENSE)。仓库还包含采用其他许可证的第三方代码；请阅读 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)，并保留相应的版权和许可证声明。

`bunny-modem` 保留 GPL-3.0 许可和完整源码。包含该模块的组合应用按 GPL-3.0 条件分发；
这不会移除原创文件已有的 Apache-2.0 声明。对应许可证见 [bunny-modem/LICENSE](bunny-modem/LICENSE)。
