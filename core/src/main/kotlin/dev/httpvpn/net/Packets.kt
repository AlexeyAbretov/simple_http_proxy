package dev.httpvpn.net

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

class Ipv4Packet(
    val src: Int,
    val dst: Int,
    val protocol: Int,
    val headerLen: Int,
    val totalLen: Int,
    val raw: ByteArray,
)

class TcpSegment(
    val srcIp: Int,
    val dstIp: Int,
    val srcPort: Int,
    val dstPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    val payload: ByteArray,
)

class UdpPacket(
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object Packets {
    private val ipId = AtomicInteger(1)

    fun parseIpv4(packet: ByteArray, length: Int = packet.size): Ipv4Packet? {
        if (length < 20 || packet.size < length) return null
        val versionIhl = packet[0].toInt() and 0xFF
        val version = versionIhl ushr 4
        val headerLen = (versionIhl and 0x0F) * 4
        if (version != 4 || headerLen < 20 || length < headerLen) return null
        val total = u16(packet, 2)
        if (total < headerLen || total > length) return null
        val frag = u16(packet, 6)
        if ((frag and 0x1FFF) != 0 || (frag and 0x2000) != 0) return null
        val raw = packet.copyOf(total)
        return Ipv4Packet(
            src = ipv4At(raw, 12),
            dst = ipv4At(raw, 16),
            protocol = raw[9].toInt() and 0xFF,
            headerLen = headerLen,
            totalLen = total,
            raw = raw,
        )
    }

    fun parseTcp(ip: Ipv4Packet): TcpSegment? {
        val off = ip.headerLen
        if (ip.totalLen < off + 20) return null
        val dataOff = ((ip.raw[off + 12].toInt() and 0xFF) ushr 4) * 4
        if (dataOff < 20 || off + dataOff > ip.totalLen) return null
        val payloadLen = ip.totalLen - off - dataOff
        return TcpSegment(
            srcIp = ip.src,
            dstIp = ip.dst,
            srcPort = u16(ip.raw, off),
            dstPort = u16(ip.raw, off + 2),
            seq = u32(ip.raw, off + 4),
            ack = u32(ip.raw, off + 8),
            flags = ip.raw[off + 13].toInt() and 0xFF,
            window = u16(ip.raw, off + 14),
            payload = ip.raw.copyOfRange(off + dataOff, off + dataOff + payloadLen),
        )
    }

    fun parseUdp(ip: Ipv4Packet): UdpPacket? {
        val off = ip.headerLen
        if (ip.totalLen < off + 8) return null
        val len = u16(ip.raw, off + 4)
        if (len < 8 || off + len > ip.totalLen) return null
        return UdpPacket(
            srcPort = u16(ip.raw, off),
            dstPort = u16(ip.raw, off + 2),
            payload = ip.raw.copyOfRange(off + 8, off + len),
        )
    }

    fun buildTcp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray = ByteArray(0),
        options: ByteArray = ByteArray(0),
    ): ByteArray {
        val optLen = (options.size + 3) and 3.inv()
        val tcpLen = 20 + optLen + payload.size
        val total = 20 + tcpLen
        val packet = ByteArray(total)
        fillIpv4(packet, total, protocol = 6, srcIp = srcIp, dstIp = dstIp)
        val tcp = 20
        putU16(packet, tcp, srcPort)
        putU16(packet, tcp + 2, dstPort)
        putU32(packet, tcp + 4, seq)
        putU32(packet, tcp + 8, ack)
        packet[tcp + 12] = (((20 + optLen) / 4) shl 4).toByte()
        packet[tcp + 13] = flags.toByte()
        putU16(packet, tcp + 14, window.coerceIn(0, 65535))
        if (options.isNotEmpty()) {
            options.copyInto(packet, tcp + 20, 0, minOf(options.size, optLen))
        }
        if (payload.isNotEmpty()) payload.copyInto(packet, tcp + 20 + optLen)
        putU16(packet, tcp + 16, transportChecksum(srcIp, dstIp, 6, packet, tcp, tcpLen))
        return packet
    }

    fun buildUdp(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val packet = ByteArray(total)
        fillIpv4(packet, total, protocol = 17, srcIp = srcIp, dstIp = dstIp)
        putU16(packet, 20, srcPort)
        putU16(packet, 22, dstPort)
        putU16(packet, 24, udpLen)
        if (payload.isNotEmpty()) payload.copyInto(packet, 28)
        var sum = transportChecksum(srcIp, dstIp, 17, packet, 20, udpLen)
        if (sum == 0) sum = 0xFFFF
        putU16(packet, 26, sum)
        return packet
    }

    /** ICMP port unreachable, so UDP apps fail fast and fall back to TCP. */
    fun buildIcmpPortUnreachable(original: ByteArray, length: Int): ByteArray? {
        val ip = parseIpv4(original, length) ?: return null
        val quoteLen = minOf(ip.totalLen, ip.headerLen + 8)
        val icmpLen = 8 + quoteLen
        val total = 20 + icmpLen
        val packet = ByteArray(total)
        fillIpv4(packet, total, protocol = 1, srcIp = ip.dst, dstIp = ip.src)
        packet[20] = 3
        packet[21] = 3
        ip.raw.copyInto(packet, 28, 0, quoteLen)
        putU16(packet, 22, internetChecksum(packet, 20, icmpLen))
        return packet
    }

    fun ipChecksumValid(packet: ByteArray): Boolean {
        if (packet.size < 20) return false
        val headerLen = (packet[0].toInt() and 0x0F) * 4
        if (headerLen < 20 || packet.size < headerLen) return false
        return internetChecksum(packet, 0, headerLen) == 0
    }

    fun transportChecksumValid(packet: ByteArray): Boolean {
        val ip = parseIpv4(packet) ?: return false
        val segment = ip.raw.copyOfRange(ip.headerLen, ip.totalLen)
        return transportChecksum(ip.src, ip.dst, ip.protocol, ip.raw, ip.headerLen, segment.size) == 0
    }

    fun icmpChecksumValid(packet: ByteArray): Boolean {
        val ip = parseIpv4(packet) ?: return false
        if (ip.protocol != 1) return false
        return internetChecksum(ip.raw, ip.headerLen, ip.totalLen - ip.headerLen) == 0
    }

    fun ipv4ToString(ip: Int): String =
        "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"

    fun parseIpv4String(text: String): Int? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var acc = 0
        for (part in parts) {
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            acc = (acc shl 8) or n
        }
        return acc
    }

    fun inet4OrNull(address: InetAddress): Int? {
        val bytes = address.address
        if (bytes.size != 4) return null
        return ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
    }

    fun internetChecksum(vararg parts: ByteArray): Int {
        var sum = 0L
        var pending = -1
        for (part in parts) {
            var index = 0
            var left = part.size
            if (pending >= 0 && left > 0) {
                sum += (pending shl 8) or (part[0].toInt() and 0xFF)
                index = 1
                left -= 1
                pending = -1
            }
            while (left > 1) {
                sum += ((part[index].toInt() and 0xFF) shl 8) or (part[index + 1].toInt() and 0xFF)
                index += 2
                left -= 2
            }
            if (left == 1) pending = part[index].toInt() and 0xFF
        }
        if (pending >= 0) sum += pending.toLong() shl 8
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.toInt().inv() and 0xFFFF
    }

    fun internetChecksum(data: ByteArray, offset: Int, length: Int): Int =
        internetChecksum(data.copyOfRange(offset, offset + length))

    private fun fillIpv4(packet: ByteArray, total: Int, protocol: Int, srcIp: Int, dstIp: Int) {
        packet[0] = 0x45
        putU16(packet, 2, total)
        putU16(packet, 4, ipId.getAndIncrement() and 0xFFFF)
        putU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = protocol.toByte()
        putIpv4(packet, 12, srcIp)
        putIpv4(packet, 16, dstIp)
        putU16(packet, 10, internetChecksum(packet, 0, 20))
    }

    private fun transportChecksum(
        srcIp: Int,
        dstIp: Int,
        protocol: Int,
        packet: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val pseudo = ByteArray(12)
        putIpv4(pseudo, 0, srcIp)
        putIpv4(pseudo, 4, dstIp)
        pseudo[9] = protocol.toByte()
        putU16(pseudo, 10, length)
        return internetChecksum(pseudo, packet.copyOfRange(offset, offset + length))
    }

    private fun ipv4At(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        val bits = value and 0xFFFFFFFFL
        bytes[offset] = (bits ushr 24).toByte()
        bytes[offset + 1] = (bits ushr 16).toByte()
        bytes[offset + 2] = (bits ushr 8).toByte()
        bytes[offset + 3] = bits.toByte()
    }

    private fun putIpv4(bytes: ByteArray, offset: Int, ip: Int) {
        bytes[offset] = (ip ushr 24).toByte()
        bytes[offset + 1] = (ip ushr 16).toByte()
        bytes[offset + 2] = (ip ushr 8).toByte()
        bytes[offset + 3] = ip.toByte()
    }
}
