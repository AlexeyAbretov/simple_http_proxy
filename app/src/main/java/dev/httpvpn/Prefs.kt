package dev.httpvpn

import android.content.Context
import java.util.UUID

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val bypassLan: Boolean,
)

data class LoadedStore(
    val profiles: List<VpnProfile>,
    val selectedId: String?,
    val stored: Boolean,
)

object Prefs {
    private const val NAME = "http_vpn"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_SELECTED = "selected"

    fun load(context: Context): ProxyConfig {
        val loaded = loadStore(context)
        val profile = loaded.profiles.firstOrNull { it.id == loaded.selectedId }
            ?: return ProxyConfig(host = "", port = 0, username = "", password = "", bypassLan = true)
        return ProxyConfig(
            host = profile.host,
            port = profile.port,
            username = profile.username,
            password = profile.password,
            bypassLan = profile.bypassLan,
        )
    }

    fun loadStore(context: Context): LoadedStore {
        val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_PROFILES)) {
            val profiles = ProfileCodec.decode(prefs.getString(KEY_PROFILES, "").orEmpty())
            val selected = prefs.getString(KEY_SELECTED, null)
                ?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
            return LoadedStore(profiles, selected, stored = true)
        }
        val host = prefs.getString("host", "").orEmpty()
        if (host.isEmpty()) return LoadedStore(emptyList(), null, stored = false)
        val profile = VpnProfile(
            id = UUID.randomUUID().toString(),
            host = host,
            port = prefs.getInt("port", 0),
            username = prefs.getString("user", "").orEmpty(),
            password = prefs.getString("pass", "").orEmpty(),
            bypassLan = prefs.getBoolean("bypassLan", true),
        )
        saveStore(context, listOf(profile), profile.id)
        return LoadedStore(listOf(profile), profile.id, stored = true)
    }

    fun hasStoredProfiles(context: Context): Boolean {
        return context.getSharedPreferences(NAME, Context.MODE_PRIVATE).contains(KEY_PROFILES)
    }

    fun saveStore(context: Context, profiles: List<VpnProfile>, selectedId: String?) {
        val editor = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, ProfileCodec.encode(profiles))
            .remove("host")
            .remove("port")
            .remove("user")
            .remove("pass")
            .remove("bypassLan")
        if (selectedId == null) editor.remove(KEY_SELECTED) else editor.putString(KEY_SELECTED, selectedId)
        editor.commit()
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
