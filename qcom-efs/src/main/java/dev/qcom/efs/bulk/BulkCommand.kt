package dev.qcom.efs.bulk

enum class BulkOp { WRITE, DELETE }

/**
 * One concrete operation on the modem, already resolved to a full EFS path.
 * [dataHex] is the validated hex payload for [BulkOp.WRITE]; [simTag] is the
 * display label ("SIM0"/"SIM1") for the result list.
 */
data class BulkCommand(
    val op: BulkOp,
    val efsPath: String,
    val dataHex: String?,
    val simTag: String,
)
