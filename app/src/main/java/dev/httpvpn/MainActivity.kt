package dev.httpvpn

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.httpvpn.databinding.ActivityMainBinding
import dev.httpvpn.net.HttpCodec
import dev.httpvpn.net.ProxyEndpoint
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var busy = false

    private val listener: (VpnState) -> Unit = { state ->
        if (!isDestroyed) render(state)
    }

    private val prepareVpn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            busy = false
            render(VpnRuntime.state)
            binding.statusText.text = "Разрешение на VPN не выдано"
        }
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var askedLegacyStorage = false

    private val legacyStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it } && !VpnRuntime.state.running && !busy) {
            readConfig(reportError = false)?.let { saveConfig(it) }
        }
    }

    private val openKept = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || isDestroyed) return@registerForActivityResult
        val kept = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(4096)
                val count = stream.read(buffer)
                if (count <= 0) null else KeptProxy.parse(String(buffer, 0, count, Charsets.UTF_8))
            }
        } catch (_: Exception) {
            null
        }
        if (kept == null) {
            binding.statusText.text = "В файле нет адреса и порта"
            return@registerForActivityResult
        }
        binding.hostInput.setText(kept.host)
        binding.portInput.setText(kept.port.toString())
        binding.userInput.setText(kept.username)
        saveConfig(
            ProxyConfig(
                kept.host,
                kept.port,
                kept.username,
                binding.passwordInput.text?.toString().orEmpty(),
                binding.bypassLan.isChecked,
            ),
        )
        render(VpnRuntime.state)
        binding.statusText.text = "Адрес и логин восстановлены. Пароль введите заново, если поле пустое."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val config = Prefs.load(this)
        val recalled = if (config.host.isEmpty()) KeptProxy.read(this) else null
        val host = recalled?.host ?: config.host
        val port = recalled?.port ?: config.port
        val username = recalled?.username ?: config.username
        binding.hostInput.setText(host)
        if (port in 1..65535) binding.portInput.setText(port.toString())
        binding.userInput.setText(username)
        binding.passwordInput.setText(config.password)
        binding.bypassLan.isChecked = config.bypassLan
        if (recalled != null) {
            Prefs.save(
                this,
                ProxyConfig(host, port, username, config.password, config.bypassLan),
            )
        }

        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.testButton.setOnClickListener { onTestClicked() }
        binding.batteryButton.setOnClickListener { askBattery() }
        binding.restoreButton.setOnClickListener {
            if (VpnRuntime.state.running || busy) return@setOnClickListener
            openKept.launch(arrayOf("*/*"))
        }

        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        VpnRuntime.listener = listener
        render(VpnRuntime.state)
    }

    override fun onStop() {
        if (!VpnRuntime.state.running && !busy) {
            readConfig(reportError = false)?.let { saveConfig(it) }
        }
        if (VpnRuntime.listener === listener) VpnRuntime.listener = null
        super.onStop()
    }

    private fun onConnectClicked() {
        if (VpnRuntime.state.running) {
            startService(Intent(this, HttpVpnService::class.java).setAction(HttpVpnService.ACTION_STOP))
            return
        }
        val config = readConfig() ?: return
        saveConfig(config)
        busy = true
        render(VpnRuntime.state)
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            prepareVpn.launch(prepare)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        busy = false
        ContextCompat.startForegroundService(
            this,
            Intent(this, HttpVpnService::class.java).setAction(HttpVpnService.ACTION_START),
        )
        render(VpnRuntime.state)
    }

    private fun onTestClicked() {
        if (VpnRuntime.state.running) return
        val config = readConfig() ?: return
        saveConfig(config)
        busy = true
        binding.statusText.text = "Проверка прокси…"
        render(VpnRuntime.state)
        thread(isDaemon = true) {
            val message = try {
                val endpoint = ProxyEndpoint(
                    InetAddress.getByName(config.host),
                    config.port,
                    config.username,
                    config.password,
                )
                val socket = Socket()
                try {
                    HttpCodec.establish(socket, endpoint, "example.com", 443, 10_000)
                } finally {
                    socket.close()
                }
                "Прокси открыл туннель до example.com:443"
            } catch (_: UnknownHostException) {
                "Не удалось найти адрес прокси"
            } catch (e: Exception) {
                "Прокси недоступен: ${e.message ?: "ошибка"}"
            }
            runOnUiThread {
                busy = false
                if (!isDestroyed) {
                    render(VpnRuntime.state)
                    binding.statusText.text = message
                }
            }
        }
    }

    private fun askBattery() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            binding.statusText.text = "Оптимизация батареи уже отключена"
            return
        }
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (_: Exception) {
            binding.statusText.text = "Откройте настройки батареи и исключите это приложение"
        }
    }

    private fun readConfig(reportError: Boolean = true): ProxyConfig? {
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        val portText = binding.portInput.text?.toString()?.trim().orEmpty()
        val user = binding.userInput.text?.toString()?.trim().orEmpty()
        val pass = binding.passwordInput.text?.toString().orEmpty()
        val port = portText.toIntOrNull()
        val error = when {
            host.isEmpty() -> "Укажите адрес прокси"
            host.any { it.isWhitespace() || it == '/' || it == '\\' || it == ':' } ->
                "Адрес без порта. Порт введите в отдельное поле"
            port == null || port !in 1..65535 -> "Порт от 1 до 65535"
            else -> null
        }
        if (error != null) {
            if (reportError) binding.statusText.text = error
            return null
        }
        return ProxyConfig(host, port!!, user, pass, binding.bypassLan.isChecked)
    }

    private fun render(state: VpnState) {
        if (state.running || busy) {
            binding.connectButton.text = getString(if (state.running) R.string.disconnect else R.string.connect)
        } else {
            binding.connectButton.text = getString(R.string.connect)
        }
        if (!busy) binding.statusText.text = state.message
        binding.connectButton.isEnabled = !busy
        val editable = !state.running && !busy
        binding.hostInput.isEnabled = editable
        binding.portInput.isEnabled = editable
        binding.userInput.isEnabled = editable
        binding.passwordInput.isEnabled = editable
        binding.bypassLan.isEnabled = editable
        binding.testButton.isEnabled = editable
        binding.restoreButton.isEnabled = editable
    }

    private fun saveConfig(config: ProxyConfig) {
        Prefs.save(this, config)
        val missing = KeptProxy.missingLegacyPermissions(this)
        if (!askedLegacyStorage && missing.isNotEmpty()) {
            askedLegacyStorage = true
            legacyStorage.launch(missing)
        }
    }
}
