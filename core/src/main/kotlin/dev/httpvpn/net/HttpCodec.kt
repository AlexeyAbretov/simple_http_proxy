package dev.httpvpn.net

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64

data class ProxyEndpoint(
    val address: InetAddress,
    val port: Int,
    val username: String,
    val password: String,
)

data class HttpHead(
    val code: Int,
    val reason: String,
    val headers: Map<String, String>,
    val statusLine: String,
)

object HttpCodec {
    fun establish(
        socket: Socket,
        proxy: ProxyEndpoint,
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 12_000,
    ) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.connect(InetSocketAddress(proxy.address, proxy.port), timeoutMs)
        socket.soTimeout = timeoutMs
        val out = socket.getOutputStream()
        out.write(connectRequest(targetHost, targetPort, proxy.username, proxy.password))
        out.flush()
        val head = readHead(socket.getInputStream())
        socket.soTimeout = 0
        if (head.code == 407) throw IOException("Прокси отклонил логин или пароль (407)")
        if (head.code / 100 != 2) {
            throw IOException("Прокси вернул ${head.code} ${head.reason}".trim())
        }
    }

    fun connectRequest(host: String, port: Int, username: String, password: String): ByteArray {
        require(port in 1..65535)
        require(host.isNotEmpty())
        require(host.none { it == '\r' || it == '\n' || it == ' ' || it == '/' })
        val text = buildString {
            append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append(':').append(port).append("\r\n")
            if (username.isNotEmpty() || password.isNotEmpty()) {
                val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
                append("Proxy-Authorization: Basic ").append(token).append("\r\n")
            }
            append("Proxy-Connection: Keep-Alive\r\n")
            append("\r\n")
        }
        return text.toByteArray(Charsets.ISO_8859_1)
    }

    fun dohRequest(host: String, query: ByteArray): ByteArray {
        require(host.none { it == '\r' || it == '\n' })
        val head = "POST /dns-query HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Content-Type: application/dns-message\r\n" +
            "Accept: application/dns-message\r\n" +
            "Content-Length: ${query.size}\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n"
        return head.toByteArray(Charsets.ISO_8859_1) + query
    }

    fun readHead(input: InputStream): HttpHead = parseResponse(readHeaderBlock(input))

    fun readHeaderBlock(input: InputStream): String {
        val buf = ByteArrayOutputStream(128)
        val window = ByteArray(4)
        var filled = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) throw EOFException("Прокси закрыл соединение")
            buf.write(b)
            if (filled < 4) {
                window[filled++] = b.toByte()
            } else {
                window[0] = window[1]
                window[1] = window[2]
                window[2] = window[3]
                window[3] = b.toByte()
            }
            if (filled == 4 &&
                window[0] == '\r'.code.toByte() &&
                window[1] == '\n'.code.toByte() &&
                window[2] == '\r'.code.toByte() &&
                window[3] == '\n'.code.toByte()
            ) {
                return buf.toString(Charsets.ISO_8859_1)
            }
        }
        throw IOException("Слишком длинный ответ прокси")
    }

    fun parseResponse(text: String): HttpHead {
        val lines = text.split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("HTTP/")) {
            throw IOException("Некорректный ответ прокси")
        }
        val code = parts[1].toIntOrNull() ?: throw IOException("Некорректный ответ прокси")
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return HttpHead(code, parts.getOrElse(2) { "" }, headers, statusLine)
    }

    fun readBody(input: InputStream, head: HttpHead, max: Int = 8192): ByteArray {
        val encoding = head.headers["transfer-encoding"]?.lowercase().orEmpty()
        if ("chunked" in encoding) return readChunked(input, max)
        val length = head.headers["content-length"]?.toIntOrNull()
            ?: throw IOException("В ответе нет длины")
        if (length !in 0..max) throw IOException("Некорректная длина ответа")
        return readFully(input, length)
    }

    fun readFully(input: InputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(buf, off, length - off)
            if (n < 0) throw EOFException("неожиданный конец потока")
            off += n
        }
        return buf
    }

    private fun readChunked(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = readAsciiLine(input)
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("Битый chunk")
            if (size == 0) {
                while (readAsciiLine(input).isNotEmpty()) Unit
                break
            }
            if (size < 0 || out.size() + size > max) throw IOException("Слишком большой ответ")
            out.write(readFully(input, size))
            val cr = input.read()
            val lf = input.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Битый chunk")
        }
        return out.toByteArray()
    }

    private fun readAsciiLine(input: InputStream, max: Int = 256): String {
        val buf = ByteArrayOutputStream()
        while (buf.size() < max) {
            val b = input.read()
            if (b < 0) throw EOFException("неожиданный конец потока")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(Charsets.US_ASCII)
    }
}
