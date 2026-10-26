package dev.httpvpn

import android.os.Handler
import android.os.Looper

data class VpnState(
    val running: Boolean,
    val tx: Long,
    val rx: Long,
    val message: String,
)

object VpnRuntime {
    @Volatile
    var state = VpnState(running = false, tx = 0, rx = 0, message = "VPN выключен")

    var listener: ((VpnState) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    fun publish(next: VpnState) {
        state = next
        val current = listener ?: return
        main.post {
            if (listener === current) current.invoke(next)
        }
    }
}
