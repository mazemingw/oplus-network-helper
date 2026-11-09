package dev.qcom.efs.features

import dev.qcom.efs.EfsException
import dev.qcom.efs.EfsRepository
import kotlin.coroutines.cancellation.CancellationException

/** Per-feature status, mirroring mtbtool's FeatureStatus. */
sealed interface FeatureStatus {
    data object AlreadyDisabled : FeatureStatus
    data object CanDisable : FeatureStatus
    data object Writing : FeatureStatus
    data class WriteError(val message: String) : FeatureStatus
    data class ReadError(val message: String) : FeatureStatus
}

/** Result of checking all features: status per feature id. */
data class FeatureCheck(
    val statuses: Map<String, FeatureStatus>,
)

/**
 * Storage access the checker needs. [read] returns null when the item file is
 * absent (the modem default: feature enabled). Injected so tests can fake it.
 */
interface FeatureItemAccess {
    suspend fun read(path: String): List<Int>?
    suspend fun write(path: String, bytes: List<Int>)
}

/** Real [FeatureItemAccess] backed by the daemon repository. */
class EfsFeatureAccess(private val repo: EfsRepository) : FeatureItemAccess {

    override suspend fun read(path: String): List<Int>? = try {
        repo.readInline(path).bytesAsIntList()
    } catch (e: EfsException) {
        if (e.efsErrno == 2) null else throw e // ENOENT = absent
    }

    override suspend fun write(path: String, bytes: List<Int>) {
        repo.writeFile(path, bytes.asByteArray(), item = null)
    }
}

private fun ByteArray.bytesAsIntList(): List<Int> = map { it.toInt() and 0xFF }

private fun List<Int>.asByteArray(): ByteArray = map { it.toByte() }.toByteArray()

/**
 * Checks feature state and applies disable, following mtbtool's
 * FeaturesChecker semantics:
 * - any read path absent -> CanDisable (modem default),
 * - all present + isDisabled -> AlreadyDisabled, else CanDisable,
 * - disable writes every entry, stopping at the first failure.
 */
class FeatureChecker(private val access: FeatureItemAccess) {

    suspend fun check(features: List<FeatureDef>, slot: Int): FeatureCheck {
        val statuses = mutableMapOf<String, FeatureStatus>()
        for (feature in features) {
            val perPath = mutableListOf<List<Int>?>()
            var readError: String? = null
            for (r in feature.reads) {
                try {
                    perPath += access.read(slotPath(r, slot))
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    readError = t.message ?: "read failed"
                    break
                }
            }
            if (readError != null) {
                statuses[feature.id] = FeatureStatus.ReadError(readError)
                continue
            }
            val existing = perPath.filterNotNull()
            statuses[feature.id] = if (existing.size < feature.reads.size) {
                FeatureStatus.CanDisable
            } else if (feature.isDisabled(existing)) {
                FeatureStatus.AlreadyDisabled
            } else {
                FeatureStatus.CanDisable
            }
        }
        return FeatureCheck(statuses)
    }

    /**
     * Writes the disabling bytes. Returns null on success, else an error message.
     *
     * A [NvWrite.merge] entry is resolved against the item's current contents
     * first, so the read belongs to the same step as its write: a read failure
     * is reported as a write failure for that item rather than silently
     * falling back to the fixed payload, which could re-enable other bits.
     */
    suspend fun disable(feature: FeatureDef, slot: Int): String? {
        for (w in feature.writes) {
            val path = slotPath(w.path, slot)
            try {
                val bytes = if (w.merge == null) w.bytes else w.merge(access.read(path))
                access.write(path, bytes)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return "Write failed for ${w.path.substringAfterLast('/')}"
            }
        }
        return null
    }
}
