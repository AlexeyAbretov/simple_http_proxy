package dev.httpvpn

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Текст `Download/simple-http-proxy.txt`. Пароля здесь нет: только хост, порт,
 * логин, галка локальной сети и id выбранного профиля.
 */
object KeptProfiles {
    const val HEADER = "http-vpn-profiles 2"
    const val MAX_BYTES = 65_536

    data class Profile(
        val id: String,
        val host: String,
        val port: Int,
        val username: String,
        val bypassLan: Boolean,
    )

    data class Snapshot(
        val profiles: List<Profile>,
        val selectedId: String,
    )

    fun acceptableHost(host: String): Boolean {
        return host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == '\\' || it == ':' }
    }

    fun isId(id: String): Boolean = ID.matches(id)

    fun format(snapshot: Snapshot): String = buildString {
        append(HEADER)
        append('\n')
        append("selected ")
        append(snapshot.selectedId)
        append('\n')
        for (profile in snapshot.profiles) {
            append(profile.id)
            append('\n')
            append(profile.host)
            append('\n')
            append(profile.port)
            append('\n')
            append(profile.username)
            append('\n')
            append(if (profile.bypassLan) '1' else '0')
            append('\n')
            append("---\n")
        }
    }

    fun parse(text: String): Snapshot? {
        val lines = text.trimStart('\uFEFF').replace("\r\n", "\n").replace('\r', '\n').lines()
        if (lines.firstOrNull()?.trim() == HEADER) return parseV2(lines)
        return parseLegacy(lines)
    }

    fun roundTrips(snapshot: Snapshot): Boolean = parse(format(snapshot)) == snapshot

    fun readAtMost(input: InputStream, limit: Int = MAX_BYTES): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (count <= 0) break
            out.write(buffer, 0, count)
            total += count
        }
        return out.toString(Charsets.UTF_8)
    }

    private fun parseV2(lines: List<String>): Snapshot? {
        if (lines.size < 2) return null
        val selectedLine = lines[1].trim()
        val selectedRaw = when {
            selectedLine == "selected" -> ""
            selectedLine.startsWith("selected ") -> selectedLine.removePrefix("selected ").trim()
            else -> return null
        }
        val profiles = ArrayList<Profile>()
        val seen = HashSet<String>()
        var index = 2
        while (index < lines.size && profiles.size < MAX_VPN_PROFILES) {
            if (lines[index].isBlank()) {
                index++
                continue
            }
            if (index + 4 >= lines.size) break
            val id = lines[index].trim()
            val host = lines[index + 1].trim()
            val port = lines[index + 2].trim().toIntOrNull()
            val username = lines[index + 3].trim()
            val lan = lines[index + 4].trim()
            index += 5
            if (index < lines.size && lines[index].trim() == "---") index++
            if (!isId(id) || !acceptableHost(host) || port == null || port !in 1..65535) continue
            if (lan != "0" && lan != "1") continue
            if (!seen.add(id)) continue
            profiles.add(Profile(id, host, port, username, lan == "1"))
        }
        val selected = when {
            profiles.any { it.id == selectedRaw } -> selectedRaw
            profiles.isNotEmpty() -> profiles.first().id
            else -> ""
        }
        return Snapshot(profiles, selected)
    }

    private fun parseLegacy(lines: List<String>): Snapshot? {
        val chunk = lines.take(3)
        if (chunk.size < 2) return null
        val host = chunk[0].trim()
        val port = chunk[1].trim().toIntOrNull() ?: return null
        val username = chunk.getOrNull(2)?.trim().orEmpty()
        if (!acceptableHost(host) || port !in 1..65535) return null
        return Snapshot(listOf(Profile(id = "", host, port, username, bypassLan = true)), "")
    }

    private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
}
