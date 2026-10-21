package dev.httpvpn.net

/**
 * IPv4 prefixes that cover the public internet and skip local networks:
 * 0/8, 10/8, 127/8, 169.254/16, 172.16/12, 192.168/16, plus multicast 224/3.
 */
object Routes {
    val publicInternet: List<Pair<String, Int>> = listOf(
        "1.0.0.0" to 8,
        "2.0.0.0" to 7,
        "4.0.0.0" to 6,
        "8.0.0.0" to 7,
        "11.0.0.0" to 8,
        "12.0.0.0" to 6,
        "16.0.0.0" to 4,
        "32.0.0.0" to 3,
        "64.0.0.0" to 3,
        "96.0.0.0" to 4,
        "112.0.0.0" to 5,
        "120.0.0.0" to 6,
        "124.0.0.0" to 7,
        "126.0.0.0" to 8,
        "128.0.0.0" to 3,
        "160.0.0.0" to 5,
        "168.0.0.0" to 8,
        "169.0.0.0" to 9,
        "169.128.0.0" to 10,
        "169.192.0.0" to 11,
        "169.224.0.0" to 12,
        "169.240.0.0" to 13,
        "169.248.0.0" to 14,
        "169.252.0.0" to 15,
        "169.255.0.0" to 16,
        "170.0.0.0" to 7,
        "172.0.0.0" to 12,
        "172.32.0.0" to 11,
        "172.64.0.0" to 10,
        "172.128.0.0" to 9,
        "173.0.0.0" to 8,
        "174.0.0.0" to 7,
        "176.0.0.0" to 4,
        "192.0.0.0" to 9,
        "192.128.0.0" to 11,
        "192.160.0.0" to 13,
        "192.169.0.0" to 16,
        "192.170.0.0" to 15,
        "192.172.0.0" to 14,
        "192.176.0.0" to 12,
        "192.192.0.0" to 10,
        "193.0.0.0" to 8,
        "194.0.0.0" to 7,
        "196.0.0.0" to 6,
        "200.0.0.0" to 5,
        "208.0.0.0" to 4,
    )

    fun covers(ip: Int): Boolean = publicInternet.any { (addr, prefix) ->
        matches(ip, Packets.parseIpv4String(addr) ?: error(addr), prefix)
    }

    fun matches(ip: Int, network: Int, prefix: Int): Boolean {
        if (prefix <= 0) return true
        if (prefix >= 32) return ip == network
        val mask = -1 shl (32 - prefix)
        return (ip and mask) == (network and mask)
    }
}
