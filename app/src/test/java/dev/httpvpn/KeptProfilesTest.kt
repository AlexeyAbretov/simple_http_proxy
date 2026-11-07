package dev.httpvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.UUID

class KeptProfilesTest {
    @Test
    fun v2RoundTripKeepsSelectionAndBypass() {
        val first = "11111111-1111-1111-1111-111111111111"
        val second = "22222222-2222-2222-2222-222222222222"
        val snapshot = KeptProfiles.Snapshot(
            profiles = listOf(
                KeptProfiles.Profile(first, "proxy.example", 8080, "alice", true),
                KeptProfiles.Profile(second, "other.example", 3128, "", false),
            ),
            selectedId = second,
        )
        val text = buildString {
            append("http-vpn-profiles 2\n")
            append("selected 22222222-2222-2222-2222-222222222222\n")
            append("11111111-1111-1111-1111-111111111111\n")
            append("proxy.example\n")
            append("8080\n")
            append("alice\n")
            append("1\n")
            append("---\n")
            append("22222222-2222-2222-2222-222222222222\n")
            append("other.example\n")
            append("3128\n")
            append("\n")
            append("0\n")
            append("---\n")
        }
        assertEquals(text, KeptProfiles.format(snapshot))
        assertEquals(snapshot, KeptProfiles.parse(text.replace("\n", "\r\n")))
        assertTrue(KeptProfiles.roundTrips(snapshot))
        assertFalse(text.contains("password"))
    }

    @Test
    fun legacyThreeLinesBecomeOneProfile() {
        val snapshot = KeptProfiles.parse("10.0.0.8\n8080\nuser name\n")
        assertEquals(
            KeptProfiles.Snapshot(
                listOf(KeptProfiles.Profile("", "10.0.0.8", 8080, "user name", true)),
                "",
            ),
            snapshot,
        )
    }

    @Test
    fun legacyRejectsPortInsideHost() {
        assertNull(KeptProfiles.parse("proxy.example:8080\n8080\n"))
    }

    @Test
    fun emptyV2IsAnEmptyList() {
        val snapshot = KeptProfiles.parse("http-vpn-profiles 2\nselected \n")
        assertEquals(KeptProfiles.Snapshot(emptyList(), ""), snapshot)
        assertTrue(KeptProfiles.roundTrips(KeptProfiles.Snapshot(emptyList(), "")))
    }

    @Test
    fun brokenHeaderIsNotAList() {
        assertNull(KeptProfiles.parse("http-vpn-profiles 2\nnope\n"))
        assertNull(KeptProfiles.parse("нет файла"))
    }

    @Test
    fun skipsDuplicateAndStopsAtLimit() {
        val profiles = (1..40).map { index ->
            KeptProfiles.Profile(id(index), "h$index.example", 1000 + index, "u$index", index % 2 == 0)
        }
        val withDuplicate = listOf(profiles[0]) + profiles
        val text = KeptProfiles.format(KeptProfiles.Snapshot(withDuplicate, id(40)))
        val parsed = KeptProfiles.parse(text)
        assertEquals(MAX_VPN_PROFILES, parsed!!.profiles.size)
        assertEquals(id(1), parsed.profiles.first().id)
        assertEquals(id(32), parsed.profiles.last().id)
        assertEquals(id(1), parsed.selectedId)
        assertEquals(1, parsed.profiles.count { it.id == id(1) })
    }

    @Test
    fun unknownSelectionFallsBackToFirst() {
        val snapshot = KeptProfiles.parse(
            """
            http-vpn-profiles 2
            selected 33333333-3333-3333-3333-333333333333
            11111111-1111-1111-1111-111111111111
            proxy.example
            8080
            alice
            1
            ---
            """.trimIndent(),
        )
        assertEquals("11111111-1111-1111-1111-111111111111", snapshot!!.selectedId)
    }

    @Test
    fun usernameMayBeTheSeparatorWord() {
        val id = "11111111-1111-1111-1111-111111111111"
        val snapshot = KeptProfiles.Snapshot(
            listOf(KeptProfiles.Profile(id, "proxy.example", 8080, "---", true)),
            id,
        )
        assertEquals(snapshot, KeptProfiles.parse(KeptProfiles.format(snapshot)))
    }

    @Test
    fun stripsBom() {
        val text = "\uFEFF10.1.2.3\n9050\nlogin\n"
        assertEquals("10.1.2.3", KeptProfiles.parse(text)!!.profiles.single().host)
    }

    @Test
    fun readAtMostStops() {
        val input = ByteArrayInputStream(ByteArray(100) { 'a'.code.toByte() })
        assertEquals(16, KeptProfiles.readAtMost(input, 16).length)
    }

    @Test
    fun randomIdRoundTrips() {
        val id = UUID.randomUUID().toString()
        val snapshot = KeptProfiles.Snapshot(
            listOf(KeptProfiles.Profile(id, "proxy.example", 1, "логин", false)),
            id,
        )
        assertTrue(KeptProfiles.isId(id))
        assertTrue(KeptProfiles.roundTrips(snapshot))
    }

    private fun id(index: Int): String = "00000000-0000-0000-0000-%012d".format(index)
}
