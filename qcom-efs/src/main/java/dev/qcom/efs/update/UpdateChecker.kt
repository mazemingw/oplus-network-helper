package dev.qcom.efs.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The newest release on GitHub, once it is known to be newer than what is installed. */
data class Release(
    val version: String,
    /** The tag verbatim, so the download link keeps working even if it is not a bare version. */
    val tag: String,
    val url: String,
    val notes: String,
)

/**
 * Looks up the newest GitHub release. Deliberately dependency-free:
 * [HttpURLConnection] plus the `org.json` that ships with Android, so the app
 * keeps its "no network stack" footprint apart from this one call.
 */
object UpdateChecker {

    private const val API =
        "https://api.github.com/repos/catxeon/qcom-efs-browser/releases/latest"
    private const val TIMEOUT_MS = 8_000

    /** Anything longer is a changelog, not a dialog. */
    private const val MAX_NOTES = 600

    class UpdateException(message: String) : Exception(message)

    /**
     * Returns the newest release when it is strictly newer than [current], or
     * null when the app is up to date. Throws [UpdateException] with a
     * readable reason when the lookup itself fails.
     */
    suspend fun latestNewerThan(current: String): Release? = withContext(Dispatchers.IO) {
        val body = fetch()
        val obj = try {
            JSONObject(body)
        } catch (e: Exception) {
            throw UpdateException("GitHub 返回的数据不是有效的发布信息")
        }

        val tag = obj.optString("tag_name").ifBlank {
            throw UpdateException("最新发布信息缺少版本标签")
        }
        if (compareVersions(tag, current) <= 0) return@withContext null

        Release(
            version = normalize(tag),
            tag = tag,
            url = obj.optString("html_url").ifBlank {
                "https://github.com/catxeon/qcom-efs-browser/releases"
            },
            notes = obj.optString("body").trim().let {
                if (it.length > MAX_NOTES) it.take(MAX_NOTES).trimEnd() + "…" else it
            },
        )
    }

    private fun fetch(): String {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            // GitHub rejects requests without a User-Agent.
            setRequestProperty("User-Agent", "qcom-efs-browser")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                throw UpdateException("无法连接 GitHub")
            }
            when (code) {
                200 -> Unit
                404 -> throw UpdateException("项目尚未发布安装包")
                403, 429 -> throw UpdateException(
                    "GitHub 已限制当前网络的请求频率，请稍后再试"
                )
                else -> throw UpdateException("GitHub 返回 HTTP 状态码 $code")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Drops a leading `v` so `v1.2.0` and `1.2.0` compare and display the same. */
    fun normalize(version: String): String =
        version.trim().removePrefix("v").removePrefix("V")

    /**
     * Compares two dotted versions numerically: >0 when [a] is newer than [b].
     * Missing segments count as 0, so `1.2` == `1.2.0`. A pre-release suffix
     * (`1.3.0-rc1`) sorts *before* the same release without one, per semver;
     * build metadata after `+` is ignored.
     */
    fun compareVersions(a: String, b: String): Int {
        val (coreA, preA) = split(normalize(a))
        val (coreB, preB) = split(normalize(b))

        val numsA = coreA.split('.')
        val numsB = coreB.split('.')
        for (i in 0 until maxOf(numsA.size, numsB.size)) {
            val x = numsA.getOrNull(i)?.toIntOrNull() ?: 0
            val y = numsB.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }

        // Equal cores: a pre-release is older than the finished release.
        return when {
            preA == preB -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    /** Splits `1.3.0-rc1+build7` into the numeric core and the pre-release tag. */
    private fun split(version: String): Pair<String, String?> {
        val noBuild = version.substringBefore('+')
        val dash = noBuild.indexOf('-')
        return if (dash < 0) noBuild to null
        else noBuild.take(dash) to noBuild.substring(dash + 1)
    }
}
