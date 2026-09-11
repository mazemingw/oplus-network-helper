package com.nvmex.networkhelper.util.network

private fun normalizeMatchLevel(level: String?): String {
    return level?.trim()?.lowercase()?.replace('-', '_').orEmpty()
}

fun cellMatchLevelText(level: String?): String {
    return when (normalizeMatchLevel(level)) {
        "" -> "-"

        // legacy
        "primary" -> "主匹配（精确）"
        "fallback1" -> "降级匹配1（不太准确）"
        "fallback2" -> "降级匹配2（不一定准）"
        "fallback" -> "降级匹配"

        // LTE query
        "primary_tac_eci" -> "精确匹配（TAC+ECI）"
        "primary_eci" -> "精确匹配（ECI）"
        "fallback_eci" -> "降级匹配（ECI）"
        "primary_tac_earfcn_pci_cellid" -> "精确匹配（TAC+EARFCN+PCI+CellId）"
        "fallback_tac_earfcn_pci_cellid_from_eci" -> "补偿匹配（TAC+EARFCN+PCI+CellId）"
        "fallback_enb_cellid_from_eci" -> "补偿匹配（eNodeB+CellId）"
        "fallback_tac_pci_cellid" -> "降级匹配（TAC+PCI+CellId）"
        "fallback_pci_cellid" -> "降级匹配（PCI+CellId）"
        "fallback_tac_earfcn_pci" -> "降级匹配（TAC+EARFCN+PCI）"
        "fallback_tac_earfcn" -> "降级匹配（TAC+EARFCN）"
        "enb_only_incomplete_from_eci" -> "同站数据不完整（ECI缺失）"
        "not_found_eci" -> "未匹配到（ECI）"
        "ambiguous_tac_pci_cellid" -> "候选过多（TAC+PCI+CellId）"
        "ambiguous_pci_cellid" -> "候选过多（PCI+CellId）"
        "ambiguous_tac_earfcn_pci" -> "候选过多（TAC+EARFCN+PCI）"

        // NR query
        "primary_gcell_id" -> "精确匹配（GCellId）"
        "primary_tac_arfcn_pci" -> "精确匹配（TAC+ARFCN+PCI）"
        "fallback_arfcn_pci" -> "降级匹配（ARFCN+PCI）"
        "fallback_arfcn" -> "降级匹配（ARFCN）"

        // site-query anchor levels
        "eci" -> "锚点匹配（ECI）"
        "enb_cellid_from_eci" -> "锚点匹配（eNodeB+CellId）"
        "enb_only_from_eci" -> "锚点匹配（仅 eNodeB）"
        "tac_earfcn_pci_cellid" -> "锚点匹配（TAC+EARFCN+PCI+CellId）"
        "tac_earfcn_pci_unique" -> "锚点匹配（TAC+EARFCN+PCI 唯一）"
        "tac_earfcn_pci" -> "锚点匹配（TAC+EARFCN+PCI）"
        "tac_earfcn" -> "锚点匹配（TAC+EARFCN）"
        "gcell_id" -> "锚点匹配（GCellId）"
        "tac_arfcn_pci" -> "锚点匹配（TAC+ARFCN+PCI）"
        "arfcn_pci" -> "锚点匹配（ARFCN+PCI）"
        "arfcn" -> "锚点匹配（ARFCN）"

        // common states
        "local" -> "本地缓存匹配"
        "not_found" -> "未匹配到"
        "invalid_query" -> "参数不足"
        "none" -> "无匹配"

        else -> "匹配结果"
    }
}

fun isDegradedCellMatchLevel(level: String?): Boolean {
    return when (normalizeMatchLevel(level)) {
        "fallback",
        "fallback1",
        "fallback2",
        "fallback_eci",
        "fallback_tac_earfcn_pci_cellid_from_eci",
        "fallback_enb_cellid_from_eci",
        "fallback_tac_pci_cellid",
        "fallback_pci_cellid",
        "fallback_tac_earfcn_pci",
        "fallback_tac_earfcn",
        "fallback_arfcn_pci",
        "fallback_arfcn",
        "tac_earfcn_pci_cellid",
        "tac_earfcn_pci_unique",
        "tac_earfcn_pci",
        "tac_earfcn",
        "tac_arfcn_pci",
        "arfcn_pci",
        "arfcn",
        "enb_only_incomplete_from_eci",
        "ambiguous_tac_pci_cellid",
        "ambiguous_pci_cellid",
        "ambiguous_tac_earfcn_pci" -> true

        else -> false
    }
}
