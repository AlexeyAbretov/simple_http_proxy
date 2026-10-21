package dev.httpvpn.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class TcpSessionTest {
    @Test(timeout = 15000)
    fun transfersPayloadAndCloses() {
        val link = Link()
        try {
            link.handshake(clientIsn = 1000)
            link.client(seq = 1001, ack = link.ackToServer, flags = TcpFlags.ACK or TcpFlags.PSH, payload = "hello".toByteArray())
            link.waitFor { link.uploaded() == "hello" }
            val before = link.packets.size
            link.serverSend("world".toByteArray())
            link.waitFor { link.packets.size > before }
            val data = link.tcp(link.packets.last())
            assertEquals("world", String(data.payload))
            assertTrue(Packets.ipChecksumValid(link.packets.last()))
            assertTrue(Packets.transportChecksumValid(link.packets.last()))
            link.ackToServer = Seq.add(data.seq, data.payload.size)
            link.client(seq = 1006, ack = link.ackToServer, flags = TcpFlags.ACK, payload = ByteArray(0))
            link.client(seq = 1006, ack = link.ackToServer, flags = TcpFlags.ACK or TcpFlags.FIN, payload = ByteArray(0))
            link.waitFor { link.uploadEof.get() }
            link.serverClose()
            link.waitFor { link.packets.any { link.tcp(it).flags and TcpFlags.FIN != 0 } }
            val fin = link.tcp(link.packets.last { link.tcp(it).flags and TcpFlags.FIN != 0 })
            assertEquals(1007L, fin.ack)
            link.client(seq = 1007, ack = Seq.add(fin.seq, 1), flags = TcpFlags.ACK, payload = ByteArray(0))
            assertTrue(link.retired.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("1.2.3.4:443", link.target)
        } finally {
            link.close()
        }
    }

    @Test(timeout = 15000)
    fun retransmissionIsNotWrittenTwice() {
        val link = Link()
        try {
            link.handshake(clientIsn = 1000)
            val hello = "HELLO".toByteArray()
            link.client(seq = 1001, ack = link.ackToServer, flags = TcpFlags.ACK or TcpFlags.PSH, payload = hello)
            link.waitFor { link.uploaded() == "HELLO" }
            link.client(seq = 1001, ack = link.ackToServer, flags = TcpFlags.ACK or TcpFlags.PSH, payload = hello)
            Thread.sleep(150)
            assertEquals("HELLO", link.uploaded())
            link.client(seq = 1006, ack = link.ackToServer, flags = TcpFlags.ACK or TcpFlags.PSH, payload = "!".toByteArray())
            link.waitFor { link.uploaded() == "HELLO!" }
        } finally {
            link.close()
        }
    }

    @Test(timeout = 15000)
    fun pureAckDoesNotEcho() {
        val link = Link()
        try {
            link.handshake(clientIsn = 1000, iss = 0xFFFFFFFFL)
            val count = link.packets.size
            link.client(seq = 1001, ack = link.ackToServer, flags = TcpFlags.ACK, payload = ByteArray(0))
            Thread.sleep(200)
            assertEquals(count, link.packets.size)
            assertEquals(0L, link.ackToServer)
        } finally {
            link.close()
        }
    }

    private class Link : TunnelFactory {
        val packets = ArrayList<ByteArray>()
        val uploadEof = AtomicBoolean(false)
        val retired = CountDownLatch(1)
        var target = ""
        var ackToServer = 0L
        private val uploaded = ByteArrayOutputStream()
        private val fromProxy = PipedInputStream(65536)
        private val serverToProxy = PipedOutputStream(fromProxy)
        private val toProxy = PipedInputStream(65536)
        private val sessionToProxy = PipedOutputStream(toProxy)
        private val closed = AtomicBoolean(false)
        private val session: TcpSession

        init {
            thread(isDaemon = true, name = "upload-reader") {
                val buf = ByteArray(1024)
                try {
                    while (true) {
                        val n = toProxy.read(buf)
                        if (n < 0) {
                            uploadEof.set(true)
                            break
                        }
                        synchronized(uploaded) { uploaded.write(buf, 0, n) }
                    }
                } catch (_: Exception) {
                    uploadEof.set(true)
                }
            }
            val src = Packets.parseIpv4String("10.10.0.2")!!
            val dst = Packets.parseIpv4String("1.2.3.4")!!
            session = TcpSession(
                key = FlowKey(src, 40000, dst, 443),
                factory = this,
                writeTun = { packet -> synchronized(packets) { packets.add(packet) } },
                onTx = {},
                onRx = {},
                onError = { error("session error: $it") },
                retire = { retired.countDown() },
            )
        }

        fun handshake(clientIsn: Long, iss: Long? = null) {
            val src = Packets.parseIpv4String("10.10.0.2")!!
            val dst = Packets.parseIpv4String("1.2.3.4")!!
            val started = TcpSession(
                key = session.key,
                factory = this,
                writeTun = { packet -> synchronized(packets) { packets.add(packet) } },
                onTx = {},
                onRx = {},
                onError = { error("session error: $it") },
                retire = { retired.countDown() },
                issSeed = iss,
            )
            active = started
            started.start(
                TcpSegment(src, dst, 40000, 443, clientIsn, 0, TcpFlags.SYN, 65535, ByteArray(0)),
            )
            waitFor { synchronized(packets) { packets.isNotEmpty() } }
            val synAck = tcp(packets.last())
            assertTrue(synAck.flags and TcpFlags.SYN != 0)
            assertTrue(synAck.flags and TcpFlags.ACK != 0)
            assertEquals(Seq.add(clientIsn, 1), synAck.ack)
            assertTrue(Packets.ipChecksumValid(packets.last()))
            assertTrue(Packets.transportChecksumValid(packets.last()))
            ackToServer = Seq.add(synAck.seq, 1)
            client(seq = Seq.add(clientIsn, 1), ack = ackToServer, flags = TcpFlags.ACK, payload = ByteArray(0))
        }

        private var active: TcpSession = session

        fun client(seq: Long, ack: Long, flags: Int, payload: ByteArray) {
            val src = Packets.parseIpv4String("10.10.0.2")!!
            val dst = Packets.parseIpv4String("1.2.3.4")!!
            active.offer(TcpSegment(src, dst, 40000, 443, seq, ack, flags, 65535, payload))
        }

        fun serverSend(data: ByteArray) {
            serverToProxy.write(data)
            serverToProxy.flush()
        }

        fun serverClose() {
            try { serverToProxy.close() } catch (_: Exception) {}
        }

        fun uploaded(): String = synchronized(uploaded) { uploaded.toString(Charsets.UTF_8) }

        fun tcp(packet: ByteArray): TcpSegment = Packets.parseTcp(Packets.parseIpv4(packet)!!)!!

        fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (cond()) return
                Thread.sleep(10)
            }
            error("timeout, packets=${synchronized(packets) { packets.size }} uploaded=${uploaded()}")
        }

        override fun open(targetHost: String, targetPort: Int, register: (Closeable) -> Boolean): Tunnel {
            target = "$targetHost:$targetPort"
            if (!register(Closeable { close() })) throw IOException("stopped")
            return Tunnel(
                input = fromProxy,
                output = sessionToProxy,
                shutdownOutput = { try { sessionToProxy.close() } catch (_: Exception) {} },
                close = { close() },
            )
        }

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            listOf(serverToProxy, sessionToProxy, fromProxy, toProxy).forEach {
                try { it.close() } catch (_: Exception) {}
            }
            active.close()
        }
    }
}
