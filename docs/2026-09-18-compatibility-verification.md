# 2026-09-18 兼容修复及验证

## 工程插件共享中心

- 真机：PLK110，`PLK110_17.0.0.100(SP09CN01)`，KernelSU 3.3.0。
- 复现：原版提示未找到插件；ADB Root 实际可读取 `/data/user/0/com.oplus.engineernetwork/files/plugin-release.zip`。
- 应用进程的 mountinfo 显示 `/data/data`、`/data/user` 被 tmpfs 隔离。仅插件操作在 `su --help` 声明支持时使用 `su -M` 全局挂载空间；不改变其他 Root 功能。
- Root 复制到本应用缓存后恢复应用 UID 和 SELinux 标签，framework 使用独立临时目录打包，避免混入旧 ROM 文件。
- “检测本机插件（不上传）”验证了本机插件读取及 framework 分享包打包：约 16.26 MB / 45.65 MB；没有调用签名或上传接口。
- 新 `engineer-plugin-framework-share.zip` 与旧 `plugin-release-share.zip` 实际样本均含插件和 framework。两种包均仅提取 `plugin-release.zip`；也支持直接上传的独立 Shadow 插件 ZIP。
- 安装目标为当前用户的工程应用 files 目录，设置属主、0600 和 SELinux 标签，原子替换并保留 `.nh-backup`。不覆盖 framework，不删除 Shadow 数据。
- Android toybox chown 不支持 GNU `--reference`；使用 `stat -c '%u:%g'` 读取父目录属主后显式设置。
- 用生产 `PluginArchive` 和 `PluginInstallCommand` 在隔离的 `files/nh-install-verify` 目录验证新旧真实样本，SHA-256 一致、system:system 和 system_app_data_file 标签正确、上一版备份有效。现用插件未替换。

## QoS 弹窗与重读

- 重注册暂停由 3 秒改为 1 秒；仍校验通话、飞行模式初始状态，并在取消/失败时恢复无线电。
- 真机验证：5G DATA 下行 2000 / 上行 150 Mbps；4G DATA QCI 8，下行 1000 / 上行 100 Mbps，IMS QCI 5，双向 10.2 Mbps。
- 弹窗采用属性/数据两栏，固定状态提示，DATA 4G / DATA 5G / IMS 4G / IMS 5G 和 MM 分块；按当前 Source 定位到对应 DATA 区块。NSA 仍是 LTE/EPS。
- 复制包括状态及所有区块，包括未读的空字段。保持复制/清理/重读一行、关闭另起一行。

## WiFi

- 真机连接 CH36 / 5180 MHz 时自动选中 5G，可手动切换查看其他频段。
- 长空闲信道段缩窄并显示省略号；宽信道依据扫描 centerFreq0 判断实际占用，80+80 分为两个 80 MHz 段，避免压缩正在使用的频谱。

## MTK：实验通道，尚未真机验证

- 资料：[3GPP TS 27.007 / ETSI 18.6.0](https://www.etsi.org/deliver/etsi_ts/127000_127099/127007/18.06.00_60/ts_127007v180600p.pdf)，10.1.27 / 10.1.50；[BeaRerM 项目的 AT 通道记录](https://github.com/Luckyji6/BeaRerM)。独立实现，未复制第三方源码。
- 仅识别到 MTK 平台时启用。探测空闲 ttyC0/C1/C3/C5/C6，避开已检测到的被占用节点；不抢占或结束 RIL。
- AT 会话内使用 ESUO 查询/选择协议栈并恢复初始栈；双卡无法确认栈归属时拒绝分配结果，不猜 SIM1。
- 枚举 CGDCONT 的 CID/APN，核对 COPS RAT；LTE/NSA 查询 CGEQOSRDP，SA 查询 C5GQOSRDP。
- 保留标准 kbit/s 单位和 DL/UL 顺序；GBR/MBR 绝不作为 AMBR。无 AMBR 字段就留空；不从 LTE 响应推定 5G 数据。
- 不开飞行模式、不启动或清理高通 DIAG、不缓存节点或 QoS。超时在 Root shell 内终止，关闭节点。
- 解析/命令生成单测通过，脚本使用 Android `sh -n` 验证语法（未打开调制解调器节点）。连接手机为高通，MTK 是否可读取仍需具体真机反馈。

字体依用户后续决定跳过；未打包或添加字体开关。
