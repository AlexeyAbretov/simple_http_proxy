package dev.httpvpn

/** Строки профилей в SharedPreferences. Пароль живёт только здесь, не в файле «Загрузки». */
object ProfileCodec {
    fun encode(profiles: List<VpnProfile>): String = buildString {
        for (profile in profiles.take(MAX_VPN_PROFILES)) {
            append(escape(profile.id))
            append('|')
            append(escape(profile.host))
            append('|')
            append(profile.port)
            append('|')
            append(escape(profile.username))
            append('|')
            append(escape(profile.password))
            append('|')
            append(if (profile.bypassLan) '1' else '0')
            append('\n')
        }
    }

    fun decode(text: String): List<VpnProfile> {
        val profiles = ArrayList<VpnProfile>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            if (profiles.size >= MAX_VPN_PROFILES) break
            val fields = split(line)
            if (fields.size != 6) continue
            val port = fields[2].toIntOrNull() ?: continue
            if (fields[5] != "0" && fields[5] != "1") continue
            if (!KeptProfiles.isId(fields[0])) continue
            profiles.add(
                VpnProfile(
                    id = fields[0],
                    host = fields[1],
                    port = port,
                    username = fields[3],
                    password = fields[4],
                    bypassLan = fields[5] == "1",
                ),
            )
        }
        return profiles
    }

    private fun escape(value: String): String = buildString {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '|' -> append("\\|")
                else -> append(ch)
            }
        }
    }

    private fun split(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var escaped = false
        for (ch in line) {
            if (escaped) {
                when (ch) {
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    else -> current.append(ch)
                }
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '|') {
                fields.add(current.toString())
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (escaped) current.append('\\')
        fields.add(current.toString())
        return fields
    }
}
