package dev.httpvpn.net

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

class Tunnel(
    val input: InputStream,
    val output: OutputStream,
    val shutdownOutput: () -> Unit,
    val close: () -> Unit,
)

fun interface TunnelFactory {
    fun open(targetHost: String, targetPort: Int, register: (Closeable) -> Boolean): Tunnel
}

fun interface TcpDial {
    /** Opens a TCP socket that is already tunneled through the HTTP proxy. */
    fun dial(host: String, port: Int): Socket
}
