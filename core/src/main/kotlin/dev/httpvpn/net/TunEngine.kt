package dev.httpvpn.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reads IPv4 packets from a TUN device, terminates TCP locally and forwards it through
 * an HTTP proxy. UDP port 53 is relayed as DNS; other UDP gets ICMP port unreachable.
 */
class TunEngine(
    proxy: ProxyEndpoint,
    private val tunnels: TunnelFactory,
    dial: TcpDial,
    private val writeTun: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val trace: (String) -> Unit = {},
    private val onKeepAwake: (Boolean) -> Unit = {},
) {
    val tx = AtomicLong()
    val rx = AtomicLong()

    private val proxyIp = Packets.inet4OrNull(proxy.address)
    private val proxyPort = proxy.port
    private val sessions = ConcurrentHashMap<FlowKey, TcpSession>()
    private val awakeSessions = AtomicInteger()
    private val dns = DnsRelay(dial)
    private val dnsExec = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "httpvpn-dns").apply { isDaemon = true }
    }
    private var lastDnsError = 0L

    @Volatile var running = true

    fun onPacket(packet: ByteArray, length: Int) {
        if (!running) return
        val ip = Packets.parseIpv4(packet, length) ?: return
        when (ip.protocol) {
            6 -> onTcp(ip)
            17 -> onUdp(ip)
        }
    }

    fun close() {
        running = false
        dnsExec.shutdownNow()
        dns.close()
        val copy = sessions.values.toList()
        sessions.clear()
        copy.forEach { it.close() }
    }

    private fun onTcp(ip: Ipv4Packet) {
        val seg = Packets.parseTcp(ip) ?: return
        val key = FlowKey(seg.srcIp, seg.srcPort, seg.dstIp, seg.dstPort)
        val existing = sessions[key]
        if (existing != null) {
            existing.offer(seg)
            return
        }
        val syn = seg.flags and TcpFlags.SYN != 0 && seg.flags and TcpFlags.ACK == 0
        if (!syn) {
            if (seg.flags and TcpFlags.RST == 0) reject(seg)
            return
        }
        if (sessions.size >= MAX_SESSIONS) {
            reject(seg)
            return
        }
        if (proxyIp != null && seg.dstIp == proxyIp && seg.dstPort == proxyPort) {
            reject(seg)
            return
        }
        val session = TcpSession(
            key = key,
            factory = tunnels,
            writeTun = ::safeWrite,
            onTx = { n -> tx.addAndGet(n.toLong()) },
            onRx = { n -> rx.addAndGet(n.toLong()) },
            onError = onError,
            retire = { done -> sessions.remove(key, done) },
            trace = trace,
            onAwake = ::noteAwake,
        )
        val previous = sessions.putIfAbsent(key, session)
        if (previous != null) {
            previous.offer(seg)
            return
        }
        session.start(seg)
    }

    private fun onUdp(ip: Ipv4Packet) {
        val udp = Packets.parseUdp(ip) ?: return
        if (udp.dstPort == 53) {
            if (udp.payload.size < 12) return
            val query = udp.payload
            val srcIp = ip.src
            val dstIp = ip.dst
            val srcPort = udp.srcPort
            dnsExec.execute {
                if (!running) return@execute
                noteAwake(true)
                try {
                    val answer = dns.query(query)
                    if (!running || answer.size !in 12..1400) return@execute
                    safeWrite(Packets.buildUdp(dstIp, srcIp, 53, srcPort, answer))
                } catch (e: Exception) {
                    if (!running) return@execute
                    val now = System.currentTimeMillis()
                    if (now - lastDnsError > 5_000) {
                        lastDnsError = now
                        onError("DNS: ${e.message ?: "ошибка"}")
                    }
                    trace("dns ${e.message}")
                } finally {
                    noteAwake(false)
                }
            }
            return
        }
        val icmp = Packets.buildIcmpPortUnreachable(ip.raw, ip.raw.size) ?: return
        safeWrite(icmp)
    }

    private fun reject(seg: TcpSegment) {
        val payloadLen = seg.payload.size +
            (if (seg.flags and TcpFlags.SYN != 0) 1 else 0) +
            (if (seg.flags and TcpFlags.FIN != 0) 1 else 0)
        val seq: Long
        val ack: Long
        val flags: Int
        if (seg.flags and TcpFlags.ACK != 0) {
            seq = seg.ack
            ack = 0
            flags = TcpFlags.RST
        } else {
            seq = 0
            ack = Seq.add(seg.seq, payloadLen)
            flags = TcpFlags.RST or TcpFlags.ACK
        }
        safeWrite(
            Packets.buildTcp(
                srcIp = seg.dstIp,
                dstIp = seg.srcIp,
                srcPort = seg.dstPort,
                dstPort = seg.srcPort,
                seq = seq,
                ack = ack,
                flags = flags,
                window = 0,
            ),
        )
    }

    private fun noteAwake(needed: Boolean) {
        if (needed) {
            if (awakeSessions.incrementAndGet() == 1) onKeepAwake(true)
            return
        }
        while (true) {
            val current = awakeSessions.get()
            if (current <= 0) return
            if (!awakeSessions.compareAndSet(current, current - 1)) continue
            if (current == 1) onKeepAwake(false)
            return
        }
    }

    private fun safeWrite(packet: ByteArray) {
        if (!running) return
        writeTun(packet)
    }

    private companion object {
        const val MAX_SESSIONS = 400
    }
}
