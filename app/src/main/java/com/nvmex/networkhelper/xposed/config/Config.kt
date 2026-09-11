package com.nvmex.networkhelper.xposed.config

object Config {
    const val LOG_TAG = "NetworkHelper999"
    const val TARGET_PKG = "com.nvmex.networkhelper"
    const val ENGINEER_TARGET_PKG = "com.oplus.engineernetwork"
    const val PHONE_TARGET_PKG = "com.android.phone"

    // =========================
    // NRCA 广播
    // =========================
    const val ACTION_NRCA_UPDATE = "com.nvmex.networkhelper.action.NRCA_UPDATE"
    const val ACTION_LTECA_UPDATE = "com.nvmex.networkhelper.action.LTECA_UPDATE"

    const val EXTRA_TS = "ts"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_TYPE = "type"
    const val EXTRA_CARRIERS = "carriers"
    const val EXTRA_JSON = "json"

    // =========================
    // QoS 单卡快照广播
    // =========================
    const val ACTION_QOS_UPDATE = "com.nvmex.networkhelper.action.QOS_UPDATE"
    const val EXTRA_QOS_JSON = "extra_qos_json"

    // 顶层关键字段：应用层先用这些分桶，不必先反序列化 JSON
    const val EXTRA_QOS_SUB_ID = "extra_qos_sub_id"
    const val EXTRA_QOS_RAT = "extra_qos_rat"
    const val EXTRA_QOS_CELL_ID = "extra_qos_cell_id"
    const val EXTRA_QOS_PCI = "extra_qos_pci"
    const val EXTRA_QOS_ARFCN = "extra_qos_arfcn"
    const val EXTRA_QOS_BAND = "extra_qos_band"
    const val EXTRA_QOS_TS = "extra_qos_ts"

    // =========================
    // QoS 全局事件广播（不归属单卡）
    // =========================
    const val ACTION_QOS_GLOBAL_EVENT_UPDATE =
        "com.nvmex.networkhelper.action.QOS_GLOBAL_EVENT_UPDATE"

    const val EXTRA_QOS_GLOBAL_JSON = "extra_qos_global_json"

    // =========================
    // Engineer NR Band lock command (App -> engineer process via Xposed)
    // =========================
    const val ACTION_ENGINEER_BAND_LOCK_COMMAND =
        "com.nvmex.networkhelper.action.ENGINEER_BAND_LOCK_COMMAND"
    const val EXTRA_BAND_LOCK_BANDS = "extra_band_lock_bands" // IntArray, e.g. [28, 41, 79]
    const val EXTRA_BAND_LOCK_UNLOCK = "extra_band_lock_unlock" // Boolean, true => clear bands
    const val EXTRA_BAND_LOCK_SUB_ID = "extra_band_lock_sub_id"
    const val EXTRA_BAND_LOCK_SLOT_ID = "extra_band_lock_slot_id"
    const val EXTRA_BAND_LOCK_KEY_INT = "extra_band_lock_key_int"
    const val ACTION_ENGINEER_BAND_LOCK_QUERY =
        "com.nvmex.networkhelper.action.ENGINEER_BAND_LOCK_QUERY"
    const val ACTION_ENGINEER_BAND_MODE_COMMAND =
        "com.nvmex.networkhelper.action.ENGINEER_BAND_MODE_COMMAND"
    const val EXTRA_BAND_MODE_KEY_INT = "extra_band_mode_key_int"

    // =========================
    // Engineer NR Band lock result (engineer process -> App)
    // =========================
    const val ACTION_ENGINEER_BAND_LOCK_RESULT =
        "com.nvmex.networkhelper.action.ENGINEER_BAND_LOCK_RESULT"
    const val EXTRA_BAND_LOCK_OK = "extra_band_lock_ok"
    const val EXTRA_BAND_LOCK_MESSAGE = "extra_band_lock_message"
    const val EXTRA_BAND_LOCK_PROCESS = "extra_band_lock_process"
    const val EXTRA_BAND_LOCK_BANDS_HEX = "extra_band_lock_bands_hex"
    const val ACTION_ENGINEER_BAND_LOCK_STATE =
        "com.nvmex.networkhelper.action.ENGINEER_BAND_LOCK_STATE"
    const val EXTRA_BAND_LOCK_STATE_OK = "extra_band_lock_state_ok"
    const val EXTRA_BAND_LOCK_STATE_RESULT = "extra_band_lock_state_result"
    const val EXTRA_BAND_LOCK_STATE_BANDS = "extra_band_lock_state_bands"
    const val EXTRA_BAND_LOCK_STATE_BANDS_HEX = "extra_band_lock_state_bands_hex"
    const val EXTRA_BAND_LOCK_STATE_KEY_INT = "extra_band_lock_state_key_int"
    const val EXTRA_BAND_LOCK_STATE_SUB_ID = "extra_band_lock_state_sub_id"
}
