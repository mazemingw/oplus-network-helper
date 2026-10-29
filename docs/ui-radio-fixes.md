# 首页、控制页、方向标与邻区修复

本次版本仍为 1.6.0（60）。

| 问题 | 根因 / 修改 |
| --- | --- |
| 首页切 SIM 时顶部留白 | `NetworkPanelTabs` 的水平 Pager 默认垂直居中，不同高度页面切换时被向下放置。改为顶部对齐。 |
| 频段 / 小区页不能滑动 | `QmiBandLockScreen` 改为水平 Pager，两页各自保留垂直滚动状态；标题、SIM、分页按钮与底部操作固定。 |
| Alert 消失时尾部跳变 | 旧代码测量整个悬浮区域，Alert 高度参与正文 bottom padding。现在仅测量固定的锁定 / 解锁按钮区，Alert 作为上方覆盖提示，不改变正文高度。 |
| 允许的网络制式占用过大 | 保留制式及 SA/NSA 选择，将合并模式和大陆模式开关并排，去掉重复的说明行。NR 未选中时仍隐藏其模式选项。 |
| 时域图锁定提示 | 标题右侧只显示当前展示制式的“小区 / 制式”锁定状态。读取失败、缓存超时和 SIM 不匹配显示未知，不冒充未锁定。NSA 切至 LTE 锚点时跟随展示制式。 |
| 静止时方向标不正确 | 静止时恢复高德 `LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER` 手机方向指示；达到现有行驶航向门槛后显示行驶箭头；停止后恢复方向指示，历史回读隐藏实时标记。 |
| 支持邻区却不显示 | 上一轮排除无 PCI 条目后，只剩 QMI 解码来源。补接同一 subscription 的系统 `CellInfo` 回调中有真实 PCI 的 LTE/NR 邻区，与 QMI 合并去重。仍排除无 PCI、失效时间戳、服务小区及已知其他运营商的数据，不重新引入 NRCA 频点。 |
| `1005,293,` 提示 NR PCI 范围错误 | 截图选择的是 NR 单 PCI 模式，且尾随分隔符被解析为空数值。输入多个 PCI 时自动选中 NR 多 PCI；解析接受尾随中英文逗号 / 空白，保留 0–1007、最多 64 项校验。单 PCI 模式与范围错误分开提示。 |

锁定状态复用同一个 NAS monitoring socket：加入已有只读 GET `0034`、`00d7`、`010f`，仍禁止 SET；监测批次含 RX 共 10 条，不超过原生限制 12。没有新增进程、NAS client 或频段 / 小区写入。原生源码、辅助程序和校验文件已同步更新。

主要文件：`NetworkPanelTabs.kt`、`NetworkPanelContent.kt`、`QmiBandLockScreen.kt`、`QmiProtocol.kt`、`QmiRadioSnapshot.kt`、`QmiLiveLocks.kt`、`QmiTimelineNeighbors.kt`、`TelephonyNeighbors.kt`、`TelephonySnapshotterMultiSim.kt`、`NetworkPanelUiState.kt`、`DriveCourse.kt`、`AmapMapActivity.kt`，以及 `nh-qmi.c` 和对应测试。

验证结果：396 项单元测试，393 通过、3 项既有样本测试跳过、0 失败；17 项原生模拟 QRTR 测试通过。新增覆盖多 PCI 的 `1005/293`、`0/1007`、64 项、无效输入；真实 C 编码断言所选 SIM 的 NAS socket、QRTR node / port、消息 `0x010e`、16 位 PCI、SCS mask 和 band mask。没有发现原生锁定使用错误端口的证据。

复现命令：

```powershell
.\tools\test_qmi_native.ps1 -NdkPath D:/Android/SDK/ndk/26.3.11579264
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

真机检查：

1. 在长短内容不同的两个 SIM 页面滑动和点击切换，确认网络面板均从顶部开始。
2. 在控制页左右滑动，等待 Alert 自动消失，确认原滚动位置和尾部高度稳定。
3. 时域图右侧核对当前制式状态；切换 SIM / NSA 展示，确认不借用另一张卡或另一制式的锁定结果。
4. 地图静止时转动手机应改变方向标；移动时使用行驶箭头，停止后恢复方向标。
5. 检查有实际 PCI 的邻区及快速填入候选；无 PCI 时回退近期驻留，邻区保留至少 3 秒。
6. 输入 `1005,293,`，确认自动选中 NR 多 PCI；检查发送字段是 `pci_list:[1005,293]`，边界 `0,1007` 可通过本地校验，`1008` 被拒绝。实际是否支持该锁定模式仍由基带决定。

当前 ADB 未连接手机。本轮验证不包含真机 UI、邻区上报或基带实际接受锁定；没有向真实基带发送锁定命令。
