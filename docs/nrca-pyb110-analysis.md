# PYB110 NRCA 带宽兼容修复

## 结论

样本 `PYB110_17.0.0.155(CN01)` 新增了支持 3 MHz 的 NRCA 带宽枚举。启用时在枚举起点插入 3 MHz，原来的枚举值整体加 1。因此同样的 100 MHz，旧设备上报 `14`，启用新表的设备上报 `15`。应用原来固定将 `15` 解码为 200 MHz，能够完整解释本次显示异常。

这是枚举语义变化。对比样本中的 Java/AIDL 层，未发现带宽单位由 MHz 改变、数值乘二或载波字段错位。

静态样本已确认系统支持两套表及其启用条件；当前尚未在故障真机读取属性、捕获原始回包或安装修复版本验证。

## 现有代码的数据来源

NRCA 主链路确实读取子系统服务框架，并非直接读取工程模式页面：

1. `XposedInit` 在 `com.oplus.subsys`、`com.android.phone` 和 `system_server` 安装相关 Hook。
2. `IndicationHooks` 主动通过 `com.oplus.radio.RadioProxy.getNrcaInfo(Message)` 查询，在 `SubsysRadioResponse.getNrcaInfoResponse` 读取 HAL `NrcaInfo`；被动通过 `SubsysRadioIndication.radioNrcaInfoChangeInd` 兜底。
3. `NrcaHandler` 反射读取 `carrierConfig[].dlBandwidth` / `ulBandwith`，把原始枚举值放入广播 JSON；拼写 `ulBandwith` 与系统字段一致。
4. 应用接收广播后交给 `NrcaTranslator` 换算显示。原映射与历史工程模式 `CaInfoConst` 的常量一致。

`HookEntry` 中 `RegistrantList` 的 `RadioNrcaInfo` 分支只做日志观察，并非另一条负责转换数据的主链路。

## 对比证据

使用用户提供的 JADX 1.5.5。新样本根目录为：

```text
C:\Users\Administrator\Desktop\OPPO\PYB110\PYB110_17.0.0.155(CN01)\engineer-plugin-framework-share
```

| 样本 | 检查内容 | 结果 |
| --- | --- | --- |
| 上级目录 `plugin-release/CellularApp-plugin-release-unsigned.apk` | `CaInfoConst` | 固定旧表，100 MHz = 14，200 MHz = 15 |
| `701/FBY/sources/.../castatus/CaInfoConst.java` | 历史工程模式常量 | 与应用旧映射一致 |
| `FBY/16.0.7.200/FBY16.0.7.200/sources/.../OplusServiceStateTrackerUtil.java` | 系统解码方法 | 仅旧表 |
| `FBY/16.0.7.206/sources/.../OplusServiceStateTrackerUtil.java` | 系统解码方法 | 仅旧表 |
| 新工程模式插件 `CaInfoConst`、`CaInfoPresenter` | 显示逻辑 | 根据能力选择偏移 0 或 1 |
| 新 `system_ext/framework/oplus-telephony-common.jar` | `OplusServiceStateTrackerUtil.getNr5gBandwidthMHz` | 同时提供旧表和包含 3 MHz 的扩展表 |
| 新 `oplus-subsystem-service.jar`、`oplus-subsystem-service-ext.jar` | `radioNrcaInfoChangeInd` | 带宽枚举原样传递，没有乘二或归一化 |
| 新 `oplus-subsystem-service.jar` | `responseNrcaInfo` | 主动查询也原样传递带宽枚举 |
| 历史与新 `Nr5gMl1Dlm2CarrierConfig` | 字段类型及 Parcel 写入顺序 | 相同，含 `dlBandwidth` / `ulBandwith` |

反编译结果保存在本地忽略目录 `tmp/nrca-analysis/`，没有将系统反编译代码加入应用源码。

## 新旧表的选择条件

新工程模式的 `EngineerSystemProperties.isSupportNr5GBw3MHZ()` 与系统电话框架采用相同判断：

```text
ro.product.oplus.cpuinfo == SM8975
    或
ro.oplus.radio.support.nr5g.bw.3mhz == 1
```

满足任一条件使用扩展表。不能仅根据 Android / ColorOS 大版本、机型名称或上报值大小统一减 1。

| 原始值 | 旧表 MHz | 扩展表 MHz |
| --- | --- | --- |
| 0 | 5 | 3 |
| 1 | 10 | 5 |
| 3 | 20 | 15 |
| 4 | 25 | 20 |
| 13 | 90 | 80 |
| 14 | 100 | 90 |
| 15 | 200 | **100** |
| 16 | 400 | 200 |
| 17 | 未知 | 400 |
| 18 | INVALID | 未知 / 不属于有效带宽 |
| 19 | 未知 | INVALID |

## 本次修复

- 新增 `NrcaBandwidthEncoding`，保留旧表，按系统的两个属性选择扩展表。读取失败默认旧表；正常应用进程反射受限时使用无需 Root 的 `getprop` 回退，结果按进程缓存。
- 广播携带 `bandwidthEncoding`，原始 `dlBw` / `ulBw` 保持原值。新应用接收旧模块广播时，缺少该字段则按当前设备能力判断。
- 标签、上下行详情、频率范围、配置 / 激活总带宽、有效性判断和 CSV 导出使用同一套解码规则。
- 修正 CSV 原先独立的错误映射：它曾把旧表 `14` 写成 200 MHz，与界面不同。
- JSON / CSV 中的录制数据保存编码类型，回放使用录制时的编码，避免换设备后误解相同原始值。
- 旧录制文件没有编码字段时继续使用旧表。此前在新机上录制且缺少编码的历史文件无法仅凭原始值无歧义判断其编码，暂不自动猜测修正。

## 验证与真机复核

回归测试覆盖旧表全部有效带宽、新表的 3 / 100 / 200 / 400 MHz、两个能力条件及默认回退、保留 / 无效值、仅上行可用、广播兼容、配置与活动带宽求和、JSON / CSV 回放和旧录制文件。

验证结果：`NrcaBandwidthTest` 4 项、`NrcaRecordingTest` 4 项、现有 `HomeLogFilesTest` 4 项，共 12 项通过，无失败；`:app:assembleDebug` 构建成功。测试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

```text
:app:testDebugUnitTest --tests '*NrcaBandwidthTest' --tests '*NrcaRecordingTest' --tests '*HomeLogFilesTest'
:app:assembleDebug
```

故障真机可用以下只读命令确认启用条件：

```powershell
adb shell getprop ro.product.oplus.cpuinfo
adb shell getprop ro.oplus.radio.support.nr5g.bw.3mhz
```

更新模块并重启相关进程或设备后，LSPosed 日志 `[NRCA][parse]` 会打印 `bandwidthEncoding=with_3_mhz` 或 `legacy`。现有 `HAL-RAW` 日志可核对原始 `dlBandwidth` / `ulBandwith`。

取样建议：本次提供的子系统和电话框架 JAR 已足以定位该兼容变更，当前无需为此重新索取完整子系统 APK。若真机属性与这两个条件不符但仍出现异常，再补充同一故障设备上的属性输出、原始 NRCA 回包及工程模式同场景截图；需要确认加载关系时再通过 `adb shell pm path com.oplus.subsys` 定位并提取实际 APK / split APK。
