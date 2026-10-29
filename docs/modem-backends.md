# 多后端驱动

## 来源与范围

- [qcom-band-menu](https://github.com/Fronsipswu/qcom-band-menu)：保留现有独立 QMI helper、原有控制流程与 RX/CA 首页链路。
- [bunnylock](https://github.com/btsearch/bunnylock)：`55f47a87344fd7a42f5d903c660776b334664556`。导入完整 modem 库、QRTR JNI 和 Root/Shizuku 桥，未搬运其 Compose 页面。
- 原作者图标用于下拉菜单来源标记和页面底部致谢。源码、GPL-3.0 和修改记录保留在 `bunny-modem/`。
- 对齐现有 AGP 8.13.2、API 36、NDK 26.3 和 ARM64；Android 10 的 CellIdentity.bands 调用已作 API 保护。没有提高主应用最低版本。

## 选择与权限

“频段与小区控制”有频段与制式、小区锁定、驱动&设置三个滑动页面。
高通设备默认保留原有 QMI，其余设备默认使用 bunnylock 自动识别。选择保存到本地。
自动模式沿用上游设备探测和权限过滤；手动选择不允许静默替换成另一个驱动。

| 驱动 | 连接方式 | 限制 |
| --- | --- | --- |
| 原有高通 QMI | Root，现有独立 helper | ARM64 / Qualcomm QRTR |
| bunnylock 高通 QMI | Root 或 Shizuku Root | Shizuku ADB 不可直接访问 QRTR |
| 联发科 AT | Root AT 节点；或 Shizuku ADB 的 RIL 透传 | 取决于厂商实际暴露的服务 |
| Pixel | Root / Shizuku Root | 依赖 Tensor / Pixel 调制解调器接口 |
| Android HAL | Root / Shizuku | 通常只能锁频点，不能承诺 PCI 锁定 |

本次未修改 bunnylock 的 NR 多 PCI 线格式，也未修改原有 QMI 的格式。
两个实现之间发现的格式差异仍需目标基带协议/抓包验证，不能用假 IPC 测试替代真机接受性测试。

## 生命周期与操作

控制操作串行执行。切换前取消并等待原有首页监听关闭，再释放控制会话；不发送解锁。
射频重启和 AT 控制台在原有 QMI 模式也可使用 bunnylock 连接桥，操作前暂停原驱动，
结束后释放临时桥并重新读取原驱动。写请求失败不由宿主自动重放。

清理通道先停止自己的会话和监听。Root 下扫描已知竞争后端：

- qcom-band-menu：校验 Android 用户、安装 UID、可执行路径、token 文件路径。
- bunnylock：校验安装 APK、候选进程名，并核对 CLASSPATH 或进程 maps；因此不会误杀欧加嵌入的同名 helper。
- 停止前再次核对 PID 身份，有限等待退出；未知进程不处理，无全局 pkill。
- Shizuku ADB 下仅释放自己持有的服务，不宣称已清理外部 Root 进程。

成功提示为“已成功释放后端服务”，随后自动重连并读取当前驱动。

### 强力清理（2026-10-28）

在已知后端清理之后，新增基于实际持有描述符的 Root 扫描，不以包名作为唯一判断：

- 通过 `pidfd_getfd` 复制进程的 socket 描述符，`getsockname` 确认 AF_QIPCRTR；检查完立即关闭复制句柄，避免清理程序自己继续占用客户端。
- 同时识别 `/dev/cdc-wdm*`、`/dev/qcqmi*`、`/dev/smdcntl*` 的 QMUX 设备句柄。
- 结合系统包管理器的第三方 UID/APK、CLASSPATH、maps 和私有可执行文件路径确定归属；排除本应用、系统包和系统 UID。独立 `/data/local/tmp/` Root 工具也按实际描述符识别。
- 发送信号前重新验证 PID 启动时间和描述符；使用 pidfd 发送 TERM，有限等待后再发送 KILL，避免 PID 复用误杀。先停止已确认的第三方应用，防止后台立即重建服务。
- 内核不支持 pidfd、权限拒绝或扫描超时会写入诊断并提示覆盖不完整；保留原有已知后端清理回退，不关闭 SELinux，不杀 rild/厂商无线服务，不宣称清理了无法确认的对象。

关闭 Linux 进程只能释放它实际持有的通道；不能据此承诺清除基带内部残留、持久化锁定或修复每一种 QMI 5。自动刷新仍失败时保留具体错误。

2026-10-29 增加 [系统组件恢复面板](qmi-system-recovery.md)：普通清理失败后列出已知 NAS 系统使用者，
经用户确认后用 init 临时停启，逐项验证实际客户端容量；普通清理扫描仍不杀系统进程。
“替我决定”默认开启，仅预选厂商扩展；“自动恢复”默认关闭，启用后按暂停归属恢复。

### 统一控制界面

删除独立 bunnylock 控制页面，使用 `UnifiedModemControls.kt` 将后端能力、回读和命令映射到原来的频段、小区表单及悬浮按钮。HAL 不支持 PCI 时只生成频点组件；MTK 单 PCI、bunnylock QMI 多 PCI 按能力显示。gNB 白名单仍由原有 QMI 提供。

NRCA 由每 SIM/当前小区的单一裁决器输出：工程模式优先，坏帧不清除原有有效报告，不延长原报告时间戳；原有 12 秒有效期结束后才回退 QMI。可通过 `NrCaSource` 日志查看切换原因和年龄。

### APK 体积变化

接入后端时启用了 `app/build.gradle.kts` 的 `jniLibs.useLegacyPackaging = true`，
让 Root/app_process 能从提取目录加载 QRTR JNI。原来的 `.so` 在 APK 中不压缩，
现在压缩存储、安装时提取，因此下载包缩小不代表功能减少，也不代表安装占用同比缩小。

核对本地 `app/release/app-release.apk`：总大小 27,300,136 字节；其中高德
`libAMapSDK_MAP_v10_0_600.so` 原始 19,998,832 字节，压缩后 7,673,367 字节。
旧版保留的 Debug APK 中同一高德库为不压缩的 19,998,832 字节。
仅这一项就节省约 12.33 MB；新增代码、DEX 和其他文件会抵消一部分。
没有找到用户所说旧 36 MB Release 的对应文件，因此不把全部约 9 MB 差额逐字节归因。

本轮按要求仅做 Debug 编译验证，未对手机执行强力清理或锁定，未新增或运行测试。

## 验证

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
node tools/test_qmi_recovery.mjs
.\tools\test_qmi_native.ps1 -NdkPath D:/Android/SDK/ndk/26.3.11579264
```

`ModemDriverTest` 检查自动识别、无 Root 权限过滤、手动选择不替换、PCI 0/1007 与非法输入。
`BunnyBackendDispatchTest` 在 Android 上以假 IPC 驱动真实 ModemController，检查 MTK RIL、
Pixel、HAL 权限、NR 多 PCI、AT、射频重启的路由。该测试不启动 su、不连接调制解调器。
清理脚本测试使用实际打包函数与假 Android 命令/信号，不操作真实进程。

真机验收请分别使用 MTK、Pixel、普通 HAL 和 Qualcomm 设备：授权 Root/Shizuku，检查
自动选择、两张 SIM、频段与小区回读、断开后重连、AT 回复，以及射频重启恢复。
Root 与 Shizuku ADB 都需测试；不能将一台 Qualcomm 的结果当作其他平台已验证。
