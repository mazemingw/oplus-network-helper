package dev.qcom.efs

/** Human-readable labels only; modem paths, payloads and protocol values stay untouched. */
internal fun efsTypeLabel(type: String): String = when (type) {
    "dir" -> "目录"
    "file" -> "普通文件"
    "item" -> "NV 项目文件"
    "link" -> "符号链接"
    "unknown" -> "未知"
    else -> type
}

/** Localize errors at the presentation boundary without changing parser or DIAG contracts. */
internal fun chineseMessage(message: String): String {
    val status = when (message) {
        "OK" -> "成功"
        "busy" -> "忙碌"
        "bad command" -> "无效命令"
        "NV full" -> "NV 空间已满"
        "failed" -> "失败"
        "not active" -> "尚未激活"
        "bad parameter" -> "参数无效"
        "read-only" -> "只读"
        "not defined" -> "未定义"
        "unknown" -> "未知"
        else -> null
    }
    if (status != null) return status
    var text = message
    for ((english, chinese) in messageTranslations) text = text.replace(english, chinese)
    return text
}

private val messageTranslations = listOf(
    "cannot reach the helper on" to "无法连接后台服务",
    "the helper rejected the request" to "后台服务拒绝了请求",
    "not connected to the helper" to "尚未连接后台服务",
    "lost the connection to the helper" to "与后台服务的连接已断开",
    "the helper closed the connection" to "后台服务已关闭连接",
    "use Reconnect in the menu" to "请在菜单中选择“重新连接”",
    "malformed answer from the helper" to "后台服务返回的数据格式错误",
    "the helper did not start" to "后台服务未能启动",
    "not connected to the modem - connect first" to "尚未连接基带，请先连接",
    "read-only mode is on - unlock writes first" to "当前为只读模式，请先启用写入",
    "Write failed for " to "写入失败：",
    "read failed" to "读取失败",
    "timed out" to "连接超时",
    "No sim slot key found in JSON" to "JSON 中没有 SIM 卡槽键",
    "Unknown top-level key(s): " to "未知的顶层键：",
    "Expected object for slot key: " to "卡槽键对应的值必须是对象：",
    "Expected object for path: " to "路径对应的值必须是对象：",
    "Expected object for entry: " to "文件条目必须是对象：",
    "Missing or invalid 'op' for entry: " to "条目的 op 字段缺失或无效：",
    "Missing 'data' for write op at: " to "写入操作缺少 data 字段：",
    "data must not be empty for write op at " to "写入操作的 data 字段不能为空：",
    "Unknown op '" to "未知的操作类型 '",
    "Invalid hex data (odd length) for: " to "十六进制字符数必须为偶数：",
    "Invalid hex data (non-hex chars) for: " to "数据包含非十六进制字符：",
    "Malformed JSON: " to "JSON 格式错误：",
    "unexpected end of input" to "输入内容意外结束",
    "Unterminated object" to "对象缺少结束标记",
    "Unterminated string" to "字符串缺少结束引号",
    "Unsupported JSON value at " to "此位置存在不支持的 JSON 值：",
    "Expected '{' at " to "此位置应为左花括号：",
    "Expected ':' at " to "此位置应为冒号：",
    "Expected ',' or '}' at " to "此位置应为逗号或右花括号：",
    "Expected '\"' at " to "此位置应为双引号：",
    "is not a hex digit" to "不是十六进制字符",
    "hex digits: one is missing from the last byte" to "个十六进制字符：最后一个字节缺少一位",
    "out of memory" to "内存不足",
    "the SPC must be exactly six digits" to "SPC 必须恰好为六位数字",
    "the SPC must be six digits" to "SPC 必须为六位数字",
    "SPC exchange failed" to "SPC 通信失败",
    "could not flush the EFS journal, so the modem was left alone" to "EFS 日志同步失败，因此未重启基带",
    "timed out waiting for a DIAG response" to "等待 DIAG 响应超时",
    "the modem does not publish DIAG on the QRTR bus (DIAG there belongs to other processors: " to "基带未在 QRTR 上发布 DIAG（发现的 DIAG 属于其他处理器：",
    "no DIAG service (4097) published on the QRTR bus" to "QRTR 总线上没有发布 DIAG 服务（4097）",
    "this kernel has no QRTR support" to "当前内核不支持 QRTR",
    "so there is no bus to reach the modem through" to "因此无法通过此总线连接基带",
    "does not exist (this kernel has no diagchar driver)" to "不存在（当前内核未包含 diagchar 驱动）",
    "QRTR lookup failed" to "QRTR 服务查询失败",
    "QRTR getsockname failed" to "获取 QRTR 地址失败",
    "the target rejected the command" to "基带拒绝了此命令",
    "the current access level does not allow this command" to "当前访问权限不允许执行此命令",
    "security state does not allow this command" to "当前安全状态不允许执行此命令",
    "NV write failed" to "NV 写入失败",
    "NV full" to "NV 空间已满",
    "is not readable on this target" to "无法在此设备上读取",
    "short reply" to "响应数据长度不足",
    "Permission denied" to "权限不足",
    "Operation not permitted" to "操作不被允许",
    "No such file or directory" to "文件或目录不存在",
    "File exists" to "文件已存在",
    "No space left on device" to "设备剩余空间不足",
    "Read-only file system" to "文件系统为只读",
    "Directory not empty" to "目录非空",
    "Broken pipe" to "连接管道已断开",
    "Connection refused" to "连接被拒绝",
    "Connection reset" to "连接已重置",
    "Connection timed out" to "连接超时",
    "Socket closed" to "连接已关闭",
    "Software caused connection abort" to "连接已中断",
    "Bad file descriptor" to "文件描述符无效",
    "Invalid argument" to "参数无效",
).sortedByDescending { it.first.length }
