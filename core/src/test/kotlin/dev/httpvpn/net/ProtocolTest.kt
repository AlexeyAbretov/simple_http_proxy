package dev.httpvpn.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ProtocolTest {
    @Test
    fun wikipediaIpv4Checksum() {
        val header = hex("450000730000400040110000c0a80001c0a800c7")
        assertEquals(0xb861, Packets.internetChecksum(header))
        val withSum = hex("45000073000040004011b861c0a80001c0a800c7")
        assertEquals(0, Packets.internetChecksum(withSum))
    }

    @Test
    fun sequenceNumbersWrap() {
        assertTrue(Seq.lt(1, 2))
        assertFalse(Seq.lt(2, 1))
        assertTrue(Seq.lte(5, 5))
        assertTrue(Seq.lt(0xFFFFFFFFL, 0))
        assertFalse(Seq.lt(0, 0xFFFFFFFFL))
        assertEquals(0L, Seq.add(0xFFFFFFFFL, 1))
        assertEquals(1L, Seq.ahead(0, 0xFFFFFFFFL))
    }

    @Test
    fun ipv4TextRoundTrip() {
        for (text in listOf("8.8.8.8", "192.168.1.1", "255.255.255.255", "10.10.0.2")) {
            assertEquals(text, Packets.ipv4ToString(Packets.parseIpv4String(text)!!))
        }
    }

    @Test
    fun publicRoutesSkipLan() {
        assertTrue(Routes.covers(ip("8.8.8.8")))
        assertTrue(Routes.covers(ip("1.1.1.1")))
        assertTrue(Routes.covers(ip("172.15.255.255")))
        assertTrue(Routes.covers(ip("172.32.0.1")))
        assertTrue(Routes.covers(ip("100.64.0.1")))
        assertFalse(Routes.covers(ip("10.1.1.1")))
        assertFalse(Routes.covers(ip("127.0.0.1")))
        assertFalse(Routes.covers(ip("172.16.0.1")))
        assertFalse(Routes.covers(ip("192.168.0.1")))
        assertFalse(Routes.covers(ip("169.254.1.1")))
        assertFalse(Routes.covers(ip("224.0.0.1")))
        assertFalse(Routes.covers(ip("0.0.0.1")))
    }

    @Test
    fun builtPacketsHaveValidChecksums() {
        val src = ip("10.10.0.2")
        val dst = ip("1.2.3.4")
        val tcp = Packets.buildTcp(
            src, dst, 40000, 443, 1000, 1,
            TcpFlags.SYN or TcpFlags.ACK, 65535,
            options = byteArrayOf(2, 4, 0x05, 0xB4.toByte()),
        )
        assertTrue(Packets.ipChecksumValid(tcp))
        assertTrue(Packets.transportChecksumValid(tcp))
        val parsed = Packets.parseTcp(Packets.parseIpv4(tcp)!!)!!
        assertEquals(1000L, parsed.seq)
        assertEquals(1L, parsed.ack)
        assertEquals(443, parsed.dstPort)

        val udp = Packets.buildUdp(dst, src, 53, 1111, byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(Packets.ipChecksumValid(udp))
        assertTrue(Packets.transportChecksumValid(udp))
        val icmp = Packets.buildIcmpPortUnreachable(udp, udp.size)!!
        assertTrue(Packets.ipChecksumValid(icmp))
        assertTrue(Packets.icmpChecksumValid(icmp))
    }

    @Test
    fun connectRequestCarriesBasicAuth() {
        val bytes = HttpCodec.connectRequest("1.2.3.4", 443, "user", "пароль")
        val text = bytes.toString(Charsets.ISO_8859_1)
        val expected = Base64.getEncoder().encodeToString("user:пароль".toByteArray(Charsets.UTF_8))
        assertTrue(text.startsWith("CONNECT 1.2.3.4:443 HTTP/1.1\r\n"))
        assertTrue(text.contains("Proxy-Authorization: Basic $expected\r\n"))
        assertFalse(text.contains("пароль"))
    }

    @Test
    fun readsContentLengthAndChunkedBodies() {
        val fixed = ByteArrayInputStream(
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nping".toByteArray(),
        )
        val head = HttpCodec.readHead(fixed)
        assertEquals(200, head.code)
        assertArrayEquals("ping".toByteArray(), HttpCodec.readBody(fixed, head))

        val chunked = ByteArrayInputStream(
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nwiki\r\n0\r\n\r\n".toByteArray(),
        )
        val chunkHead = HttpCodec.readHead(chunked)
        assertArrayEquals("wiki".toByteArray(), HttpCodec.readBody(chunked, chunkHead))
    }

    @Test
    fun proxyRejectsBadPassword() {
        val server = ServerSocket(0)
        val accept = thread(isDaemon = true) {
            val socket = server.accept()
            socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray())
            socket.getOutputStream().flush()
        }
        val client = Socket()
        try {
            HttpCodec.establish(
                client,
                ProxyEndpoint(InetAddress.getLoopbackAddress(), server.localPort, "user", "nope"),
                "1.2.3.4",
                443,
                2000,
            )
            error("expected failure")
        } catch (e: Exception) {
            assertTrue(e.message.orEmpty().contains("407"))
        } finally {
            client.close()
            server.close()
            accept.join(1000)
        }
    }

    @Test
    fun dnsOverTcpIsCached() {
        val accepts = AtomicInteger()
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            try {
                val socket = server.accept()
                accepts.incrementAndGet()
                val head = HttpCodec.readHeaderBlock(socket.getInputStream())
                assertTrue(head.contains("CONNECT 8.8.8.8:53"))
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                val lenBuf = HttpCodec.readFully(socket.getInputStream(), 2)
                val length = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
                val query = HttpCodec.readFully(socket.getInputStream(), length)
                val response = query.copyOf()
                response[2] = (response[2].toInt() or 0x80).toByte()
                socket.getOutputStream().write(byteArrayOf((response.size ushr 8).toByte(), response.size.toByte()))
                socket.getOutputStream().write(response)
                socket.getOutputStream().flush()
            } catch (_: Exception) {
            }
        }
        val relay = DnsRelay(
            dial = TcpDial { _, _ ->
                val socket = Socket()
                HttpCodec.establish(
                    socket,
                    ProxyEndpoint(InetAddress.getLoopbackAddress(), server.localPort, "", ""),
                    "8.8.8.8",
                    53,
                    2000,
                )
                socket
            },
            dohTargets = emptyList(),
            tcpServers = listOf("8.8.8.8"),
        )
        try {
            val first = dnsQuery(0x12, 0x34)
            val second = dnsQuery(0x56, 0x78)
            val a = relay.query(first)
            val b = relay.query(second)
            assertEquals(0x12, a[0].toInt() and 0xFF)
            assertEquals(0x34, a[1].toInt() and 0xFF)
            assertEquals(0x56, b[0].toInt() and 0xFF)
            assertEquals(0x78, b[1].toInt() and 0xFF)
            assertEquals(1, accepts.get())
        } finally {
            relay.close()
            server.close()
        }
    }

    private fun dnsQuery(id0: Int, id1: Int): ByteArray {
        val query = ByteArray(12)
        query[0] = id0.toByte()
        query[1] = id1.toByte()
        query[2] = 1
        query[5] = 1
        return query
    }

    private fun ip(text: String) = Packets.parseIpv4String(text)!!

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            out[i] = text.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
