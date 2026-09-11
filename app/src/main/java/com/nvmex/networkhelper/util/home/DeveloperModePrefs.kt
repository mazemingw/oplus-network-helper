package com.nvmex.networkhelper.util.home

import android.content.Context

object DeveloperModePrefs {

    private const val SP_NAME = "developer_mode_prefs"
    private const val KEY_ROLE = "role"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_AUTH_EXPIRES_AT = "auth_expires_at"

    enum class Role(val value: String) {
        NONE("none"),
        DEVELOPER("developer"),
        SUPER_ADMIN("super_admin")
    }

    private fun sp(context: Context) =
        context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean {
        return getRole(context) != Role.NONE
    }

    fun isSuperAdmin(context: Context): Boolean {
        return getRole(context) == Role.SUPER_ADMIN
    }

    fun getRole(context: Context): Role {
        val raw = sp(context).getString(KEY_ROLE, Role.NONE.value).orEmpty()
        return when (raw) {
            Role.DEVELOPER.value -> Role.DEVELOPER
            Role.SUPER_ADMIN.value -> Role.SUPER_ADMIN
            else -> Role.NONE
        }
    }

    fun saveRole(context: Context, role: Role) {
        sp(context).edit()
            .putString(KEY_ROLE, role.value)
            .apply()
    }

    fun saveAuthSession(
        context: Context,
        role: Role,
        authToken: String?,
        expiresAtMs: Long?
    ) {
        sp(context).edit()
            .putString(KEY_ROLE, role.value)
            .putString(KEY_AUTH_TOKEN, authToken?.trim().orEmpty())
            .putLong(KEY_AUTH_EXPIRES_AT, expiresAtMs ?: 0L)
            .apply()
    }

    fun getAuthToken(context: Context): String {
        return sp(context).getString(KEY_AUTH_TOKEN, "").orEmpty()
    }

    fun isAuthTokenValid(context: Context): Boolean {
        val token = getAuthToken(context)
        val exp = sp(context).getLong(KEY_AUTH_EXPIRES_AT, 0L)
        if (token.isBlank() || exp <= 0L) return false
        return System.currentTimeMillis() < exp
    }

    fun clear(context: Context) {
        sp(context).edit()
            .putString(KEY_ROLE, Role.NONE.value)
            .putString(KEY_AUTH_TOKEN, "")
            .putLong(KEY_AUTH_EXPIRES_AT, 0L)
            .apply()
    }
}
