package com.nvmex.networkhelper.ui.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.nvmex.networkhelper.ui.onboarding.EnvironmentProbeStore

class XposedSettingsProvider : ContentProvider() {

    companion object {
        private const val AUTHORITY = "com.nvmex.networkhelper.xposed.settings"
        private const val PATH_PLUGIN_HELP = "plugin_help"
        private const val MATCH_PLUGIN_HELP = 1
        private const val MATCH_ENVIRONMENT_PROBE = 2
        private const val COL_ENABLED = "enabled"
    }

    private val matcher by lazy {
        UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_PLUGIN_HELP, MATCH_PLUGIN_HELP)
            addURI(AUTHORITY, EnvironmentProbeStore.PATH_ENVIRONMENT_PROBE, MATCH_ENVIRONMENT_PROBE)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        return when (matcher.match(uri)) {
            MATCH_PLUGIN_HELP -> {
                val ctx = context ?: return MatrixCursor(arrayOf(COL_ENABLED))
                val enabled = AppSettingsStore.read(ctx).enablePluginHelp
                MatrixCursor(arrayOf(COL_ENABLED)).apply {
                    addRow(arrayOf(if (enabled) 1 else 0))
                }
            }
            MATCH_ENVIRONMENT_PROBE -> {
                val columns = arrayOf(
                    EnvironmentProbeStore.COL_PACKAGE,
                    EnvironmentProbeStore.COL_LABEL,
                    EnvironmentProbeStore.COL_PROCESS,
                    EnvironmentProbeStore.COL_LAST_SEEN_MS,
                    EnvironmentProbeStore.COL_ACTIVE
                )
                val ctx = context ?: return MatrixCursor(columns)
                MatrixCursor(columns).apply {
                    EnvironmentProbeStore.readStatuses(ctx).forEach { status ->
                        addRow(
                            arrayOf<Any?>(
                                status.packageName,
                                status.label,
                                status.processName.orEmpty(),
                                status.lastSeenMs,
                                if (status.active) 1 else 0
                            )
                        )
                    }
                }
            }
            else -> throw IllegalArgumentException("Unknown uri: $uri")
        }
    }

    override fun getType(uri: Uri): String {
        return when (matcher.match(uri)) {
            MATCH_PLUGIN_HELP -> "vnd.android.cursor.item/vnd.${context?.packageName}.plugin_help"
            MATCH_ENVIRONMENT_PROBE -> "vnd.android.cursor.dir/vnd.${context?.packageName}.environment_probe"
            else -> throw IllegalArgumentException("Unknown uri: $uri")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Read-only provider")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return when (matcher.match(uri)) {
            MATCH_ENVIRONMENT_PROBE -> {
                val ctx = context ?: return 0
                val packageName = values?.getAsString(EnvironmentProbeStore.COL_PACKAGE)
                    ?: return 0
                val processName = values.getAsString(EnvironmentProbeStore.COL_PROCESS)
                val lastSeenMs = values.getAsLong(EnvironmentProbeStore.COL_LAST_SEEN_MS)
                    ?: System.currentTimeMillis()
                EnvironmentProbeStore.markSeen(ctx, packageName, processName, lastSeenMs)
                1
            }
            else -> throw UnsupportedOperationException("Read-only provider")
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("Read-only provider")
    }
}
