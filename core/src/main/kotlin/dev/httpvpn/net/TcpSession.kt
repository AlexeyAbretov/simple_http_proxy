package dev.httpvpn.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int)

/**
 * One phone TCP connection terminated locally and spliced onto an HTTP CONNECT tunnel.
 * The phone's stack talks to this session; the session talks to the proxy.
 */
class TcpSession(
    val key: FlowKey,
    private val factory: TunnelFactory,
    private val writeTun: (ByteArray) -> Unit,
    private val onTx: (Int) -> Unit,
    private val onRx: (Int) -> Unit,
    private val onError: (String) -> Unit,
    private val retire: (TcpSession) -> Unit,
    private val trace: (String) -> Unit = {},
    private val issSeed: Long? = null,
) {
    private val events = LinkedBlockingQueue<Ev>(64)
    private val lock = Any()
    private val resources = ArrayList<Closeable>()
    private val unacked = ArrayDeque<Sent>()
    private val up = ByteQueue()
    private val down = ByteQueue()

    @Volatile private var closed = false
    private var established = false
    private var clientFin = false
    private var proxyEof = false
    private var finSent = false
    private var outShutdown = false
    private var iss = 0L
    private var sndUna = 0L
    private var sndNxt = 0L
    private var rcvNxt = 0L
    private var peerRightEdge = 0L
    private var lastActive = System.nanoTime()
    private var lastProbe = 0L
    private var upstream: OutputStream? = null
    private var shutdownOut: (() -> Unit)? = null

    fun start(syn: TcpSegment) {
        thread(name = "tcp-${key.dstPort}", isDaemon = true) {
            try {
                run(syn)
            } catch (_: Stop) {
            } catch (e: Exception) {
                if (!closed) {
                    try { sendRst() } catch (_: Exception) {}
                    onError(e.message ?: "Ошибка соединения")
                }
            } finally {
                closed = true
                closeResources()
                retire(this)
            }
        }
    }

    fun offer(seg: TcpSegment) {
        if (closed) return
        events.offer(Ev.Seg(seg))
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            val copy = resources.toList()
            resources.clear()
            copy.forEach { try { it.close() } catch (_: Exception) {} }
        }
        events.offer(Ev.Fail("stopped"))
    }

    fun register(resource: Closeable): Boolean {
        synchronized(lock) {
            if (closed) {
                try { resource.close() } catch (_: Exception) {}
                return false
            }
            resources.add(resource)
            return true
        }
    }

    private fun run(syn: TcpSegment) {
        iss = issSeed ?: (Random.nextInt().toLong() and 0xFFFFFFFFL)
        sndUna = iss
        sndNxt = iss
        rcvNxt = Seq.add(syn.seq, 1)
        lastActive = System.nanoTime()
        sendSynAck()
        peerRightEdge = Seq.add(sndNxt, syn.window)
        trace("connect ${Packets.ipv4ToString(key.dstIp)}:${key.dstPort}")
        val tunnel = factory.open(Packets.ipv4ToString(key.dstIp), key.dstPort, ::register)
        upstream = tunnel.output
        shutdownOut = tunnel.shutdownOutput
        startReader(tunnel.input)
        loop()
    }

    private fun startReader(input: InputStream) {
        thread(name = "tcp-read-${key.dstPort}", isDaemon = true) {
            val buf = ByteArray(8192)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) {
                        putEvent(Ev.Eof)
                        break
                    }
                    if (n > 0) putEvent(Ev.Data(buf.copyOf(n)))
                }
            } catch (e: Exception) {
                if (!closed) putEvent(Ev.Fail(e.message ?: "соединение с прокси прервано"))
            }
        }
    }

    private fun putEvent(ev: Ev) {
        while (!closed) {
            if (events.offer(ev, 200, TimeUnit.MILLISECONDS)) return
        }
    }

    private fun loop() {
        while (!closed) {
            val first = events.poll(300, TimeUnit.MILLISECONDS)
            if (first == null) {
                if (closed) break
                retransmit()
                probeWindow()
                idleCheck()
                continue
            }
            handle(first)
            while (!closed) {
                handle(events.poll() ?: break)
            }
        }
    }

    private fun handle(ev: Ev) {
        if (closed) return
        when (ev) {
            is Ev.Seg -> onSegment(ev.seg)
            is Ev.Data -> onProxyData(ev.bytes)
            Ev.Eof -> onProxyEof()
            is Ev.Fail -> if (ev.message != "stopped") fail(ev.message, reset = true)
        }
    }

    private fun onSegment(seg: TcpSegment) {
        if (closed) return
        lastActive = System.nanoTime()
        if (seg.flags and TcpFlags.RST != 0) throw Stop()
        if (seg.flags and TcpFlags.ACK != 0) onAck(seg.ack, seg.window)
        if (closed || !established) {
            if (!established && seg.flags and TcpFlags.SYN != 0) retransmitNow()
            return
        }
        if (seg.flags and TcpFlags.SYN != 0) {
            sendAck()
            return
        }
        handlePayload(seg)
    }

    private fun onAck(ack: Long, window: Int) {
        if (Seq.lt(sndNxt, ack)) {
            sendAck()
            return
        }
        if (Seq.lt(ack, sndUna)) return
        sndUna = ack
        while (unacked.isNotEmpty()) {
            val seg = unacked.first()
            val end = Seq.add(seg.seq, seg.seqCount)
            if (Seq.lte(end, ack)) unacked.removeFirst() else break
        }
        if (!established && Seq.lte(Seq.add(iss, 1), ack)) {
            established = true
            trace("established ${Packets.ipv4ToString(key.dstIp)}:${key.dstPort}")
        }
        peerRightEdge = Seq.add(ack, window)
        pumpSend()
        maybeDestroy()
    }

    private fun handlePayload(seg: TcpSegment) {
        var payload = seg.payload
        val fin = seg.flags and TcpFlags.FIN != 0
        var duplicate = false
        if (Seq.lt(seg.seq, rcvNxt)) {
            val skip = Seq.ahead(rcvNxt, seg.seq)
            if (skip > payload.size.toLong()) {
                sendAck()
                return
            }
            payload = if (skip == 0L) payload else payload.copyOfRange(skip.toInt(), payload.size)
            duplicate = true
        } else if (Seq.lt(rcvNxt, seg.seq)) {
            sendAck()
            return
        }
        if (payload.isEmpty() && !fin) {
            if (duplicate) sendAck()
            return
        }
        if (payload.isNotEmpty()) {
            if (up.size + payload.size > UP_CAP) {
                sendAck()
                return
            }
            up.append(payload)
            rcvNxt = Seq.add(rcvNxt, payload.size)
        }
        if (fin) {
            if (!clientFin) {
                clientFin = true
                rcvNxt = Seq.add(rcvNxt, 1)
            }
            sendAck()
            flushUp()
            maybeSendFin()
            maybeDestroy()
            return
        }
        sendAck()
        flushUp()
    }

    private fun onProxyData(bytes: ByteArray) {
        down.append(bytes)
        onRx(bytes.size)
        lastActive = System.nanoTime()
        pumpSend()
    }

    private fun onProxyEof() {
        proxyEof = true
        maybeSendFin()
    }

    private fun pumpSend() {
        if (!established || closed) return
        while (down.size > 0) {
            val room = sendRoom()
            if (room <= 0) break
            val n = minOf(room, MSS, down.size)
            enqueueSend(TcpFlags.ACK or TcpFlags.PSH, down.take(n), EMPTY, n)
        }
        maybeSendFin()
    }

    private fun maybeSendFin() {
        if (finSent || !proxyEof || !established || closed) return
        if (down.size > 0 || unacked.isNotEmpty()) return
        enqueueSend(TcpFlags.ACK or TcpFlags.FIN, EMPTY, EMPTY, 1)
        finSent = true
        maybeDestroy()
    }

    private fun maybeDestroy() {
        if (clientFin && finSent && unacked.isEmpty() && down.size == 0 && up.size == 0) {
            throw Stop()
        }
    }

    private fun flushUp() {
        val before = ourWindow()
        if (up.size > 0) {
            val data = up.take(up.size)
            val out = upstream ?: return
            out.write(data)
            out.flush()
            onTx(data.size)
        }
        if (!outShutdown && clientFin && up.size == 0) {
            try { shutdownOut?.invoke() } catch (_: Exception) {}
            outShutdown = true
        }
        if (ourWindow() > before) sendAck()
    }

    private fun sendSynAck() {
        enqueueSend(TcpFlags.SYN or TcpFlags.ACK, EMPTY, MSS_OPTION, 1)
    }

    private fun enqueueSend(flags: Int, payload: ByteArray, options: ByteArray, seqCount: Int) {
        if (closed) return
        val seq = sndNxt
        writeRaw(seq, flags, payload, options)
        unacked.addLast(
            Sent(
                seq = seq,
                flags = flags,
                payload = payload,
                options = options,
                seqCount = seqCount,
                sentAt = System.nanoTime(),
                rtoNs = RTO_NS,
                retries = 0,
            ),
        )
        sndNxt = Seq.add(sndNxt, seqCount)
        lastActive = System.nanoTime()
    }

    private fun sendAck() {
        if (closed) return
        writeRaw(sndNxt, TcpFlags.ACK, EMPTY, EMPTY)
    }

    private fun sendRst() {
        if (closed) return
        writeRaw(sndNxt, TcpFlags.RST or TcpFlags.ACK, EMPTY, EMPTY)
    }

    private fun writeRaw(seq: Long, flags: Int, payload: ByteArray, options: ByteArray) {
        writeTun(
            Packets.buildTcp(
                srcIp = key.dstIp,
                dstIp = key.srcIp,
                srcPort = key.dstPort,
                dstPort = key.srcPort,
                seq = seq,
                ack = rcvNxt,
                flags = flags,
                window = ourWindow(),
                payload = payload,
                options = options,
            ),
        )
    }

    private fun retransmitNow() {
        val seg = unacked.firstOrNull() ?: return
        writeRaw(seg.seq, seg.flags, seg.payload, seg.options)
        seg.sentAt = System.nanoTime()
    }

    private fun retransmit() {
        val seg = unacked.firstOrNull() ?: return
        val now = System.nanoTime()
        if (now - seg.sentAt < seg.rtoNs) return
        if (seg.retries >= 8) {
            fail(null, reset = true)
            return
        }
        writeRaw(seg.seq, seg.flags, seg.payload, seg.options)
        seg.sentAt = now
        seg.retries += 1
        seg.rtoNs = minOf(seg.rtoNs * 2, 2_000_000_000L)
    }

    private fun probeWindow() {
        if (!established || closed || unacked.isNotEmpty() || down.size == 0 || sendRoom() > 0) return
        val now = System.nanoTime()
        if (now - lastProbe < 1_000_000_000L) return
        val byte = down.take(1)
        enqueueSend(TcpFlags.ACK or TcpFlags.PSH, byte, EMPTY, 1)
        lastProbe = now
    }

    private fun idleCheck() {
        if (System.nanoTime() - lastActive > IDLE_NS) fail(null, reset = true)
    }

    private fun sendRoom(): Int {
        val distance = Seq.ahead(peerRightEdge, sndNxt)
        if (distance > 0x80000000L) return 0
        return minOf(distance.toInt(), 65535)
    }

    private fun ourWindow(): Int = (UP_CAP - up.size).coerceIn(0, 65535)

    private fun fail(message: String?, reset: Boolean): Nothing {
        if (reset && !closed) {
            try { sendRst() } catch (_: Exception) {}
        }
        if (!message.isNullOrEmpty() && !closed) onError(message)
        throw Stop()
    }

    private fun closeResources() {
        val copy = synchronized(lock) {
            val items = resources.toList()
            resources.clear()
            items
        }
        copy.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private class Sent(
        val seq: Long,
        val flags: Int,
        val payload: ByteArray,
        val options: ByteArray,
        val seqCount: Int,
        var sentAt: Long,
        var rtoNs: Long,
        var retries: Int,
    )

    private class Stop : RuntimeException()

    private sealed class Ev {
        class Seg(val seg: TcpSegment) : Ev()
        class Data(val bytes: ByteArray) : Ev()
        class Fail(val message: String) : Ev()
        data object Eof : Ev()
    }

    private companion object {
        const val MSS = 1460
        const val UP_CAP = 256 * 1024
        const val RTO_NS = 400_000_000L
        const val IDLE_NS = 15L * 60 * 1_000_000_000L
        val EMPTY = ByteArray(0)
        val MSS_OPTION = byteArrayOf(2, 4, 0x05, 0xB4.toByte())
    }
}
