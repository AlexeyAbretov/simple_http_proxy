package dev.httpvpn

data class VpnProfile(
    val id: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val bypassLan: Boolean,
)

const val MAX_VPN_PROFILES = 32
