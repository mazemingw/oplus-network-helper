package dev.qcom.efs.features

/** A checked switch means the capability is not configured as disabled. */
fun featureSwitchChecked(status: FeatureStatus?): Boolean = status !is FeatureStatus.AlreadyDisabled

fun featureEditBlockReason(connected: Boolean, backedUp: Boolean, readOnly: Boolean): String? = when {
    !connected -> "请先连接基带后再操作。"
    !backedUp -> "请先完成本地与云端备份，再开启“允许修改”。"
    readOnly -> "尚未允许修改，请先开启上方的“允许修改”开关。"
    else -> null
}

fun featureRisk(id: String): String = when (id) {
    "r17_2t2t" -> "禁用后可能减少可用上行组合，降低上传速率。"
    "r16_2t1t" -> "禁用后可能减少上行聚合组合并影响上传速度。"
    "ul_mimo" -> "禁用后可能降低上传峰值速率；与 TDD MIMO、低频 4Rx 共用配置。"
    "fdd_ul_mimo" -> "禁用后可能降低 FDD 频段上传速度。"
    "tdd_ul_mimo" -> "禁用后可能降低 TDD 上传速度；共用配置可能影响上行 MIMO、低频 4Rx。"
    "nr_1024qam_tdd" -> "禁用后可能降低高信号质量下的峰值速率。"
    "nr_ulca" -> "禁用后可能减少同时使用的上行载波并降低上传速度。"
    "dl_nrca" -> "禁用后可能降低 5G 下载速度。"
    "lowband_4rx" -> "禁用后可能降低弱覆盖下的接收性能；与上行 MIMO 共用配置。"
    "nsa_tf_nrca" -> "禁用后可能减少 NSA 可用载波组合并降低速度。"
    "nsa_ff_nrca" -> "禁用后可能减少 NSA 可用载波组合并降低速度。"
    "nsa_tt_nrca" -> "禁用后可能减少 NSA 可用载波组合并降低速度。"
    "segmentation" -> "禁用后可能影响能力上报及部分 5G 接入或聚合场景。"
    "dss" -> "禁用后可能无法使用运营商的 DSS 5G 覆盖。"
    else -> "禁用后可能影响网络表现，具体取决于机型和网络。"
}

data class FeatureValueReference(val path: String, val enabled: String, val disabled: String, val note: String?)

fun featureValueReferences(feature: FeatureDef, slot: Int, originals: List<OriginalItem>): List<FeatureValueReference> =
    feature.writes.map { write ->
        val path = slotPath(write.path, slot)
        val original = originals.firstOrNull { it.path == path }
        val enabled = when {
            original == null -> "恢复本机禁用前原值；暂无有效原值记录，不能统一按 00/01 填写。"
            original.hex == null -> "删除该项（禁用前文件不存在，使用基带默认值）。"
            original.hex.isEmpty() -> "保留空文件（禁用前为 0 字节）。"
            else -> original.hex.chunked(2).joinToString(" ").uppercase()
        }
        FeatureValueReference(path, enabled,
            write.bytes.joinToString(" ") { "%02X".format(it) },
            if (write.merge != null) "已有文件仅清除第 30 字节（下标 29），其余字节保留；下方是新建文件的禁用模板，不能整段覆盖现有文件。" else null)
    }
