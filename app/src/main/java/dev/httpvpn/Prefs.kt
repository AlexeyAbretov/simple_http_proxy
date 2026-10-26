package dev.httpvpn

import android.content.Context

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val bypassLan: Boolean,
)

object Prefs {
    private const val NAME = "http_vpn"

    fun load(context: Context): ProxyConfig {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return ProxyConfig(
            host = prefs.getString("host", "").orEmpty(),
            port = prefs.getInt("port", 0),
            username = prefs.getString("user", "").orEmpty(),
            password = prefs.getString("pass", "").orEmpty(),
            bypassLan = prefs.getBoolean("bypassLan", true),
        )
    }

    fun save(context: Context, config: ProxyConfig) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("user", config.username)
            .putString("pass", config.password)
            .putBoolean("bypassLan", config.bypassLan)
            .commit()
        KeptProxy.write(context, config.host, config.port, config.username)
    }

    fun allFilesDeclined(context: Context): Boolean {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .getBoolean("allFilesDeclined", false)
    }

    fun setAllFilesDeclined(context: Context, declined: Boolean) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("allFilesDeclined", declined)
            .commit()
    }
}
