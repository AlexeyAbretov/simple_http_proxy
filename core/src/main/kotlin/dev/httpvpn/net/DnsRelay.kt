package dev.httpvpn.net

import java.io.IOException
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.Base64

data class DohTarget(val ip: String, val host: String)

/**
 * Resolves DNS queries through the proxy. DoH over TLS is tried first because many
 * HTTP proxies block port 53; DNS-over-TCP is the fallback.
 */
class DnsRelay(
    private val dial: TcpDial,
    private val dohTargets: List<DohTarget> = DEFAULT_DOH,
    private val tcpServers: List<String> = DEFAULT_TCP,
    private val ttlMs: Long = 60_000,
) {
    private val lock = Any()
    private val cache = HashMap<String, Cached>()
    private var doh: Doh? = null

    fun query(query: ByteArray): ByteArray {
        if (query.size < 12 || query.size > 1500) throw IOException("некорректный DNS-запрос")
        val key = cacheKey(query)
        synchronized(lock) {
            val hit = cache[key]
            if (hit != null && hit.expiresAt > System.currentTimeMillis()) {
                return rewrite(hit.answer, query)
            }
        }
        val answer = fetch(query)
        synchronized(lock) {
            if (cache.size > 256) cache.clear()
            cache[key] = Cached(answer.copyOf(), System.currentTimeMillis() + ttlMs)
        }
        return rewrite(answer, query)
    }

    fun close() {
        val socket = synchronized(lock) {
            val current = doh?.socket
            doh = null
            current
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun fetch(query: ByteArray): ByteArray {
        var last: Exception? = null
        if (dohTargets.isNotEmpty()) {
            for (target in dohTargets) {
                repeat(2) {
                    try {
                        return dohExchange(target, query)
                    } catch (e: Exception) {
                        last = e
                        close()
                    }
                }
            }
        }
        for (server in tcpServers) {
            try {
                return tcpExchange(server, query)
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("DNS недоступен")
    }

    private fun dohExchange(target: DohTarget, query: ByteArray): ByteArray {
        val socket = ensureDoh(target)
        socket.soTimeout = 8_000
        val out = socket.getOutputStream()
        out.write(HttpCodec.dohRequest(target.host, query))
        out.flush()
        val head = HttpCodec.readHead(socket.getInputStream())
        if (head.code / 100 != 2) throw IOException("DoH ${head.code}")
        val body = HttpCodec.readBody(socket.getInputStream(), head)
        if (body.size < 12) throw IOException("короткий DNS-ответ")
        return body
    }

    private fun ensureDoh(target: DohTarget): Socket {
        synchronized(lock) {
            val current = doh
            if (current != null && current.host == target.host && !current.socket.isClosed) {
                return current.socket
            }
        }
        val tcp = dial.dial(target.ip, 443)
        tcp.soTimeout = 8_000
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val ssl = factory.createSocket(tcp, target.host, 443, true) as SSLSocket
        ssl.soTimeout = 8_000
        try {
            ssl.startHandshake()
        } catch (e: Exception) {
            try { ssl.close() } catch (_: Exception) {}
            throw e
        }
        synchronized(lock) {
            val current = doh
            if (current != null && !current.socket.isClosed) {
                try { ssl.close() } catch (_: Exception) {}
                return current.socket
            }
            doh = Doh(target.host, ssl)
            return ssl
        }
    }

    private fun tcpExchange(server: String, query: ByteArray): ByteArray {
        val socket = dial.dial(server, 53)
        socket.soTimeout = 8_000
        try {
            val out = socket.getOutputStream()
            out.write(byteArrayOf((query.size ushr 8).toByte(), query.size.toByte()))
            out.write(query)
            out.flush()
            val lenBuf = HttpCodec.readFully(socket.getInputStream(), 2)
            val length = ((lenBuf[0].toInt() and 0xFF) shl 8) or (lenBuf[1].toInt() and 0xFF)
            if (length !in 12..4096) throw IOException("некорректный DNS-ответ")
            return HttpCodec.readFully(socket.getInputStream(), length)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun cacheKey(query: ByteArray): String =
        Base64.getEncoder().encodeToString(query.copyOfRange(2, query.size))

    private fun rewrite(answer: ByteArray, query: ByteArray): ByteArray {
        val out = answer.copyOf()
        out[0] = query[0]
        out[1] = query[1]
        return out
    }

    private class Doh(val host: String, val socket: Socket)
    private class Cached(val answer: ByteArray, val expiresAt: Long)

    companion object {
        val DEFAULT_DOH = listOf(
            DohTarget("1.1.1.1", "cloudflare-dns.com"),
            DohTarget("8.8.8.8", "dns.google"),
        )
        val DEFAULT_TCP = listOf("8.8.8.8", "1.1.1.1")
    }
}
