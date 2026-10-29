# 路测隧道惯导与采样修复记录

本次沿实际路测入口检查了高德定位、传感器融合、惯导、定时双卡采样、Room、CSV、历史回读、频段筛选和地图绘制。没有重写导航架构，没有放宽校准、传感器超时、采样周期或定位精度阈值。

## 根因与证据

下表是修复前可由代码及确定性输入复现的故障路径。当前没有连接用户手机，不能把模拟回放结果视为该手机的隧道实测验收。

| 文件 / 函数 | 触发条件与证据 | 修复 |
| --- | --- | --- |
| `util/map/DriveSampler.kt` / `sampleOnce`；`ui/map/AmapMapActivity.kt` / 采样器构造 | `getLatLng() ?: return` 位于读取双卡快照、组批、持久化之前；Activity 的位置回调超过 5 秒便返回空。惯导未就绪或未进入地图位置时，连无线样本也完全不保存。 | 每个周期独立读取无线快照；位置决策为空时仍原子写入双卡样本，坐标为 SQL NULL，来源为 NONE。 |
| `ui/map/AmapMapActivity.kt` / `onMotionReading`、`activate`；原 `util/map/DriveCourse.kt` / `drivePreferInertial` | `sdkFresh` 包含网络定位，不区分可靠 GPS；只要网络回调每 3 秒内到达就阻止惯导位置进入 `acceptMapPosition`。原精度比较允许精度数值小的网络定位覆盖惯导。 | 可靠 GPS、新鲜惯导、原有 SDK 备用定位分开仲裁；网络定位不能顶掉有效惯导。先执行原有 GPS 时间、精度、跳变检查，再决定是否重新锚定。 |
| `util/map/DriveMotionTracker.kt` / `onSensorChanged` | 原条件要求 `acceleration.timestamp - rotation.timestamp` 和 `acceleration.timestamp - gyro.timestamp` 都在 `0..200`。例如加速度 1000 ms、已到达的姿态 1010 ms、陀螺仪 1005 ms，会一直被拒绝，`engine.onMotion` 收不到数据，行驶中也无法完成校准。 | 保留 200 ms 同步窗口，接受窗口内不同传感器流的先后交错；缺少参考和超过窗口仍拒绝。姿态重新对齐时更新可靠性，游戏姿态失可靠后重新建立参考，并检查加速度传感器可靠性。 |
| `util/map/DriveLocationPolicy.kt` / `driveFixElapsedMillis`；Activity 原采样位置缓存 | 两种测量时间戳都缺失时，原代码使用回调到达时间；最近 GPS 也能被多个采样周期重复用作新位置。 | 缺少测量时间拒绝；显式拒绝高德缓存类型和重复测量时间。每条测量最多用于一个双卡批次，估算点使用最新 IMU 积分时间，不能靠定时器刷新旧坐标时间。 |
| `util/map/DrivePointEntity.kt`、`model/map/DrivePoint.kt` | 坐标非空的数据结构无法表达“已采到无线数据，但没有有效位置”。 | 坐标改为可空，增加 `positionSource`；数据库 6→7 迁移保留旧数据和主键，旧估算点标记为 INERTIAL。 |

其他排查结论：

- `DriveSampler.start` 本来就是独立协程定时器，不依赖 GPS 回调；原来的问题是周期内部提前返回。现将循环提取为生产与测试共用的小函数，并记录失败，避免静默异常。
- `DeadReckoningEngine` 没有固定 60 秒或 200 米漂移退出条件。默认推算上限仍为 10 分钟，遵守原设置的 1–30 分钟；显式设置 1 分钟时仍应按用户配置到期。漂移指示只展示，不作为 200 米截止条件。
- 本页实际使用 `AmapMapActivity` 与 `DriveSampler` 调用高德 Marker/Polyline 绘制，不是 Compose 状态直接生成地图点。频段筛选、视野裁剪、最多 4000 个标记及抽稀仅影响显示，不限制落库；折线仍使用完整有效轨迹。
- `DriveLocation` 是未用于此路测入口的旧定位辅助类，不是本次 GPS 失锁的采样入口。

## 修改后的数据链路

1. `activate` 接收高德测量，检查坐标、精度存在性、测量时间与缓存类型。
2. GPS 观测经 `DriveMotionTracker.acceptGps` 进入原 `DeadReckoningEngine.onGps` 校验、自动校准和重新锚定。是否已经校准不会否决本身有效的 GPS。
3. 姿态、陀螺仪、加速度在原时间窗口内融合，行驶中的 GPS 速度/航向变化用于学习偏差，无须停车或手动校准。失可靠/超时后不凭空恢复校准。
4. `DrivePositionSelector` 每个采样周期读取最新引擎结果，不依赖地图是否刷新：可靠 GPS 在原 3 秒窗口内优先，失锁超过 3 秒后有效 IMU 返回 INERTIAL。没有有效估算时保留原 SDK 备用定位精度门槛和独立测量检查。
5. `DriveSamplingBatch` 在同一个时间戳下为所有 SIM 使用同一份位置决策。即使位置为 NONE，也保存每张卡的无线快照；不复用已消费的上一条 GPS 测量。
6. `DrivePointWriteQueue` 保持写入顺序；`DrivePointDao.recordBatch` 保持双卡事务及会话里程统计。失败会记录具体异常。
7. 地图、轨迹长度、覆盖率、历史回读跳过空坐标，并在缺位置处断开连线。频段筛选不删除持久化样本。
8. 有效坐标、双 SIM、Session 和原 Falcon 元数据保持；无位置样本不交给 Falcon 伪造坐标。CSV 原列保持，末尾追加 `position_source`，NONE 样本的坐标列为空；旧 CSV 仍可导入。

保留“自动隧道惯性估算”开关。关闭后不选 INERTIAL，继续原 SDK/GPS 方式；无新有效位置时仍保存无线数据。

本次修改文件：

- 入口与选择：`ui/map/AmapMapActivity.kt`、`util/map/DriveLocationPolicy.kt`、`DriveMotionTracker.kt`、`DeadReckoningEngine.kt`，删除 `DriveCourse.kt` 中旧仲裁函数。
- 采样与诊断：`util/map/DriveSampler.kt`、新增 `DriveSampling.kt`、`DriveDiagnostics.kt`，以及 `DrivePointWriteQueue.kt`。
- 存储与显示兼容：`model/map/DrivePoint.kt`、`util/map/DrivePointEntity.kt`、`DrivePointDao.kt`、`Database.kt`、新增 `DrivePointMapping.kt`、`DriveGeometry.kt`、`DriveCsv.kt`、`ui/management/DataManagementActivity.kt`。
- 测试与构建：新增 `DriveTunnelPipelineTest.kt`、`DriveRoomRecordingTest.kt`、`DriveDatabaseTestRunner.kt`；更新现有惯导、定位策略、几何、CSV、频段筛选、基站快照测试以覆盖策略或可空坐标；`app/build.gradle.kts` 开启 Debug BuildConfig 并配置 Android 测试依赖与隔离测试 Runner。

## 可重复测试

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
```

`DriveTunnelPipelineTest` 使用虚拟时钟、20 Hz IMU、行驶 GPS、双 SIM 快照，调用生产的选择器、采样循环、批次构造、异步写入队列、数据库映射、CSV、历史筛选及几何显示选择函数：

- 正常行驶自动校准，然后中断 GPS，30、60、120、300、600 秒仍有移动的 INERTIAL 双卡样本。
- 120 秒的漂移指示超过 200 米仍采样；到原默认 10 分钟之后只保存无线样本，不伪造位置。
- 传感器中断/超时、恢复传感器但没有 GPS 锚点、GPS 恢复及重新校准。
- 精确网络定位、重复测量、旧 GPS、坐标无效、精度超限、拒绝的 GPS 和开关关闭。
- 定时器单周期异常、取消传播、空坐标写入映射、CSV 空坐标往返、历史频段筛选和缺位置处断线。

`DriveRoomRecordingTest` 使用 Android 生成的 Room DAO 与 SQLite，隔离 Application，不启动 Root/高德/热点初始化。可在测试设备或模拟器执行（替换序列号）：

```powershell
adb -s <SERIAL> install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s <SERIAL> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <SERIAL> shell am instrument -w -r -e class com.nvmex.networkhelper.util.map.DriveRoomRecordingTest com.nvmex.networkhelper.test/com.nvmex.networkhelper.util.map.DriveDatabaseTestRunner
```

覆盖双卡与空坐标真实写入/回读、里程不跨无定位区间、CSV、双卡事务回滚，以及实际 v6 表迁移到 v7 后主键、自增、坐标、快照和会话信息保留。测试仅使用内存库或独立临时数据库，不修改用户会话。

本次结果：单元测试 388 项，385 通过、3 项既有样本依赖测试跳过、0 失败；Android 36 模拟器上的 3 项 Room 测试实际执行通过（非仅测试枚举）；Debug APK 和测试 APK 均构建成功。构建日志为 `build/tunnel-fix-build.log`，SQLite 测试日志为 `build/tunnel-room-test.log`。安装包保留于 `build/packages/NetworkHelper-1.6.0-60-tunnel-sampling-debug.apk`。

## 真机验收

1. 打开自动隧道惯性估算，保持原采样周期和推算时长设置。正常行驶至面板显示惯导已就绪，再进入隧道。
2. 抓取 Debug 日志：`adb logcat -v time -s DriveTest > tunnel-drive.log`。每周期应有 `position`、`sample`、`persist`；内容包含来源、测量时间、`imuReady`、校准状态、最近 GPS 时间及年龄、IMU 年龄、推算时长、拒绝原因、双卡行数和落库结果。
3. 失锁后越过 3 秒窗口，应看到 `source=INERTIAL`、`imuReady=true`、GPS 年龄增长；30/60/120 秒持续写入，两张活动 SIM 每周期通常是 `radio=2 located=2`。点的信息窗和 CSV 均显示 INERTIAL。
4. 若 IMU 未校准、失可靠或中断，记录 `sensorState`、`frameAligned`、`calibration` 及 `reason`；预期仍是 `radio=2 located=0` 并持久化，不重复上一条 GPS 坐标。
5. 出隧道后检查 GPS 重新锚定、恢复 GPS 来源；停止并保存 Session，回读及筛选 LTE/NR 频段，确认估算轨迹可见。导出的 NONE 行应保留无线字段而坐标为空。
6. 关闭自动隧道惯性估算重复验证：不会生成 INERTIAL 点，无新有效定位期间仍有无线样本。

模拟器 SQLite 与虚拟 IMU 回放不能验证手机实际传感器的质量；真机若仍未就绪，上述日志可以直接区分参考姿态、传感器时序、精度、校准和定位源拒绝的具体原因。
