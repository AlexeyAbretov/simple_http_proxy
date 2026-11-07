package dev.httpvpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileCodecTest {
    @Test
    fun passwordRoundTripKeepsReservedCharacters() {
        val password = "a|b\\c\nd\rпароль"
        val profile = VpnProfile(
            id = "11111111-1111-1111-1111-111111111111",
            host = "proxy.example",
            port = 8080,
            username = "user|name",
            password = password,
            bypassLan = false,
        )
        val draft = VpnProfile(
            id = "22222222-2222-2222-2222-222222222222",
            host = "",
            port = 0,
            username = "",
            password = "",
            bypassLan = true,
        )
        assertEquals(listOf(profile, draft), ProfileCodec.decode(ProfileCodec.encode(listOf(profile, draft))))
    }

    @Test
    fun skipsBrokenLineAndExtraProfiles() {
        val profile = VpnProfile(
            id = "11111111-1111-1111-1111-111111111111",
            host = "proxy.example",
            port = 1,
            username = "",
            password = "secret",
            bypassLan = true,
        )
        val extra = (2..40).map { index ->
            profile.copy(id = "00000000-0000-0000-0000-%012d".format(index), port = index)
        }
        val text = ProfileCodec.encode(listOf(profile) + extra) + "broken-line\n"
        val decoded = ProfileCodec.decode(text)
        assertEquals(MAX_VPN_PROFILES, decoded.size)
        assertEquals(profile, decoded.first())
        assertEquals("secret", decoded.first().password)
    }
}
