package dev.httpvpn

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import dev.httpvpn.databinding.ActivityMainBinding
import dev.httpvpn.net.HttpCodec
import dev.httpvpn.net.ProxyEndpoint
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var busy = false
    private var testing = false
    private val checkLock = Any()
    private var checkGeneration = 0
    private var checkSocket: Socket? = null
    private var profiles = mutableListOf<VpnProfile>()
    private var selectedId: String? = null
    private var bindingProfiles = false
    private var hadValidStored = false

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
            if (selectedId != null && !formIsBlank()) captureForm(reportError = false)
            persist()
        }
    }

    private val openKept = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || isDestroyed) return@registerForActivityResult
        val kept = try {
            contentResolver.openInputStream(uri)?.use { stream ->
                KeptProfiles.parse(KeptProfiles.readAtMost(stream))
            }
        } catch (_: Exception) {
            null
        }
        if (kept == null || kept.profiles.isEmpty()) {
            binding.statusText.text = getString(R.string.kept_bad_file)
            return@registerForActivityResult
        }
        replaceFromKept(kept)
        persist()
        showImported()
    }

    private var allFilesLaunching = false

    private val allFilesSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allFilesLaunching = false
        if (isDestroyed) return@registerForActivityResult
        if (KeptProxy.needsAllFilesAccess()) {
            Prefs.setAllFilesDeclined(this, true)
            binding.statusText.text = getString(R.string.kept_access_denied)
            return@registerForActivityResult
        }
        Prefs.setAllFilesDeclined(this, false)
        if (importFromFile()) {
            showImported()
        } else {
            binding.statusText.text = getString(R.string.kept_missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.profileList.setOnCheckedChangeListener { group, checkedId -> onProfileChecked(group, checkedId) }
        binding.addProfile.setOnClickListener { onAdd() }
        binding.deleteProfile.setOnClickListener { confirmDelete() }
        binding.connectButton.setOnClickListener { onConnectClicked() }
        binding.testButton.setOnClickListener { onTestClicked() }
        binding.batteryButton.setOnClickListener { askBattery() }
        binding.restoreButton.setOnClickListener { onRestoreClicked() }

        val loaded = Prefs.loadStore(this)
        profiles = loaded.profiles.toMutableList()
        selectedId = loaded.selectedId
        hadValidStored = profiles.any(::isValid)
        if (!loaded.stored) importFromFile()
        if (profiles.isEmpty()) {
            val id = UUID.randomUUID().toString()
            profiles.add(VpnProfile(id, "", 0, "", "", true))
            selectedId = id
        } else if (loaded.stored && hadValidStored) {
            persist()
        }
        bindProfiles()
        showSelected()

        if (Build.VERSION.SDK_INT >= 33) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        VpnRuntime.listener = listener
        render(VpnRuntime.state)
    }

    override fun onResume() {
        super.onResume()
        if (isDestroyed || VpnRuntime.state.running || busy || allFilesLaunching) return
        if (Prefs.hasStoredProfiles(this) || profiles.any(::isValid)) return
        if (KeptProxy.needsAllFilesAccess() && !Prefs.allFilesDeclined(this)) {
            binding.statusText.text = getString(R.string.kept_need_access)
            launchAllFiles()
            return
        }
        if (importFromFile()) showImported()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val id = selectedId
        if (!VpnRuntime.state.running && !busy && id != null && !formIsBlank()) {
            if (captureForm(reportError = false)) persist()
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        val id = selectedId
        if (!VpnRuntime.state.running && !busy && id != null && !formIsBlank()) {
            if (captureForm(reportError = false)) {
                persist()
                refreshLabel(id)
            }
        }
        if (VpnRuntime.listener === listener) VpnRuntime.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        if (testing) cancelTest(announce = false)
        super.onDestroy()
    }

    private fun onConnectClicked() {
        if (VpnRuntime.state.running) {
            startService(Intent(this, HttpVpnService::class.java).setAction(HttpVpnService.ACTION_STOP))
            return
        }
        if (!captureForm()) return
        val id = selectedId ?: return
        persist()
        refreshLabel(id)
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
        if (testing) {
            cancelTest(announce = true)
            return
        }
        if (VpnRuntime.state.running || busy) return
        if (!captureForm()) return
        val id = selectedId ?: return
        persist()
        refreshLabel(id)
        val profile = profiles.first { it.id == id }
        val generation = synchronized(checkLock) { ++checkGeneration }
        testing = true
        busy = true
        binding.statusText.text = "Проверка прокси…"
        render(VpnRuntime.state)
        thread(name = "proxy-check", isDaemon = true) {
            val message = checkProxy(profile, generation) ?: return@thread
            runOnUiThread {
                if (isDestroyed || !isCheckCurrent(generation)) return@runOnUiThread
                testing = false
                busy = false
                render(VpnRuntime.state)
                binding.statusText.text = message
            }
        }
    }

    private fun cancelTest(announce: Boolean) {
        val socket = synchronized(checkLock) {
            checkGeneration++
            val current = checkSocket
            checkSocket = null
            current
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        testing = false
        busy = false
        if (isDestroyed) return
        render(VpnRuntime.state)
        if (announce) binding.statusText.text = "Проверка прекращена"
    }

    private fun checkProxy(profile: VpnProfile, generation: Int): String? {
        return try {
            val endpoint = ProxyEndpoint(
                InetAddress.getByName(profile.host),
                profile.port,
                profile.username,
                profile.password,
            )
            if (!isCheckCurrent(generation)) return null
            val socket = Socket()
            val started = synchronized(checkLock) {
                if (generation != checkGeneration) {
                    false
                } else {
                    checkSocket = socket
                    true
                }
            }
            if (!started) {
                socket.close()
                return null
            }
            try {
                HttpCodec.establish(socket, endpoint, "example.com", 443, 10_000)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
                synchronized(checkLock) {
                    if (checkSocket === socket) checkSocket = null
                }
            }
            if (isCheckCurrent(generation)) "Прокси открыл туннель до example.com:443" else null
        } catch (_: UnknownHostException) {
            if (isCheckCurrent(generation)) "Не удалось найти адрес прокси" else null
        } catch (e: Exception) {
            if (isCheckCurrent(generation)) "Прокси недоступен: ${e.message ?: "ошибка"}" else null
        }
    }

    private fun isCheckCurrent(generation: Int): Boolean =
        synchronized(checkLock) { generation == checkGeneration }

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

    private fun onAdd() {
        if (VpnRuntime.state.running || busy) return
        if (profiles.size >= MAX_VPN_PROFILES) {
            binding.statusText.text = getString(R.string.profiles_limit)
            return
        }
        val current = profiles.firstOrNull { it.id == selectedId }
        if (current != null && current.host.isEmpty() && formIsBlank()) {
            binding.statusText.text = getString(R.string.fill_current)
            return
        }
        if (current != null && !captureForm()) return
        val id = UUID.randomUUID().toString()
        profiles.add(VpnProfile(id, "", 0, "", "", true))
        selectedId = id
        persist()
        bindProfiles()
        showSelected()
        render(VpnRuntime.state)
    }

    private fun confirmDelete() {
        if (VpnRuntime.state.running || busy) return
        val removeId = selectedId ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_profile_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete_profile) { _, _ ->
                if (!isDestroyed && !VpnRuntime.state.running && !busy) deleteProfile(removeId)
            }
            .show()
    }

    private fun deleteProfile(removeId: String) {
        val index = profiles.indexOfFirst { it.id == removeId }
        if (index < 0) return
        profiles.removeAt(index)
        selectedId = when {
            profiles.isEmpty() -> null
            index <= profiles.lastIndex -> profiles[index].id
            else -> profiles.last().id
        }
        persist(clearKeptIfEmpty = profiles.none(::isValid))
        bindProfiles()
        showSelected()
        render(VpnRuntime.state)
    }

    private fun onProfileChecked(group: RadioGroup, checkedId: Int) {
        if (bindingProfiles || checkedId == View.NO_ID) return
        val newId = group.findViewById<View>(checkedId)?.tag as? String ?: return
        if (newId == selectedId) return
        if (VpnRuntime.state.running || busy) {
            revertSelection()
            return
        }
        val current = profiles.firstOrNull { it.id == selectedId }
        if (current != null && current.host.isEmpty() && formIsBlank()) {
            profiles.removeAll { it.id == current.id }
            selectedId = newId
            persist()
            binding.profileList.post {
                if (isDestroyed) return@post
                bindProfiles()
                showSelected()
                render(VpnRuntime.state)
            }
            return
        }
        if (!captureForm()) {
            revertSelection()
            return
        }
        val previousId = selectedId
        selectedId = newId
        persist()
        if (previousId != null) refreshLabel(previousId)
        showSelected()
        render(VpnRuntime.state)
    }

    private fun onRestoreClicked() {
        if (VpnRuntime.state.running || busy) return
        Prefs.setAllFilesDeclined(this, false)
        if (KeptProxy.needsAllFilesAccess()) {
            binding.statusText.text = getString(R.string.kept_need_access)
            launchAllFiles()
            return
        }
        if (importFromFile()) {
            showImported()
        } else {
            openKept.launch(arrayOf("*/*"))
        }
    }

    private fun captureForm(reportError: Boolean = true): Boolean {
        val index = profiles.indexOfFirst { it.id == selectedId }
        if (index < 0) {
            if (reportError) binding.statusText.text = getString(R.string.add_profile_first)
            return false
        }
        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        val portText = binding.portInput.text?.toString()?.trim().orEmpty()
        val user = binding.userInput.text?.toString()?.trim().orEmpty()
        val pass = binding.passwordInput.text?.toString().orEmpty()
        val port = portText.toIntOrNull()
        val error = when {
            host.isEmpty() -> "Укажите адрес прокси"
            !KeptProfiles.acceptableHost(host) -> "Адрес без порта. Порт введите в отдельное поле"
            port == null || port !in 1..65535 -> "Порт от 1 до 65535"
            else -> null
        }
        if (error != null) {
            if (reportError) binding.statusText.text = error
            return false
        }
        val resolvedPort = port ?: return false
        profiles[index] = profiles[index].copy(
            host = host,
            port = resolvedPort,
            username = user,
            password = pass,
            bypassLan = binding.bypassLan.isChecked,
        )
        return true
    }

    private fun render(state: VpnState) {
        if (testing && state.running) {
            cancelTest(announce = false)
            return
        }
        binding.connectButton.text = getString(if (state.running) R.string.disconnect else R.string.connect)
        if (!busy) binding.statusText.text = state.message
        binding.connectButton.isEnabled = !busy
        val editable = !state.running && !busy
        val formEnabled = editable && selectedId != null
        binding.hostInput.isEnabled = formEnabled
        binding.portInput.isEnabled = formEnabled
        binding.userInput.isEnabled = formEnabled
        binding.passwordInput.isEnabled = formEnabled
        binding.bypassLan.isEnabled = formEnabled
        val checking = testing && !state.running
        binding.testButton.isEnabled = formEnabled || checking
        binding.testButton.text = getString(if (checking) R.string.stop_test else R.string.test_proxy)
        binding.restoreButton.isEnabled = editable
        binding.addProfile.isEnabled = editable
        binding.deleteProfile.isEnabled = editable && selectedId != null
        binding.profileList.isEnabled = editable
        for (i in 0 until binding.profileList.childCount) {
            binding.profileList.getChildAt(i).isEnabled = editable
        }
    }

    private fun formIsBlank(): Boolean {
        return binding.hostInput.text.isNullOrBlank() &&
            binding.portInput.text.isNullOrBlank() &&
            binding.userInput.text.isNullOrBlank() &&
            binding.passwordInput.text.isNullOrEmpty()
    }

    private fun isValid(profile: VpnProfile): Boolean {
        return KeptProfiles.acceptableHost(profile.host) && profile.port in 1..65535
    }

    private fun profileLabel(profile: VpnProfile): String {
        if (!isValid(profile)) return getString(R.string.new_profile)
        return if (profile.username.isEmpty()) {
            "${profile.host}:${profile.port}"
        } else {
            "${profile.host}:${profile.port} (${profile.username})"
        }
    }

    private fun bindProfiles() {
        bindingProfiles = true
        try {
            binding.profileList.removeAllViews()
            var checkedId = View.NO_ID
            for (profile in profiles) {
                val radio = MaterialRadioButton(this)
                radio.id = View.generateViewId()
                radio.tag = profile.id
                radio.text = profileLabel(profile)
                radio.maxLines = 2
                radio.ellipsize = TextUtils.TruncateAt.END
                radio.layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                )
                binding.profileList.addView(radio)
                if (profile.id == selectedId) checkedId = radio.id
            }
            if (checkedId != View.NO_ID) binding.profileList.check(checkedId)
        } finally {
            bindingProfiles = false
        }
    }

    private fun refreshLabel(id: String) {
        val profile = profiles.firstOrNull { it.id == id } ?: return
        val label = profileLabel(profile)
        for (i in 0 until binding.profileList.childCount) {
            val child = binding.profileList.getChildAt(i)
            if (child.tag == id && child is RadioButton) child.text = label
        }
    }

    private fun revertSelection() {
        bindingProfiles = true
        try {
            val radio = (0 until binding.profileList.childCount)
                .map { binding.profileList.getChildAt(it) }
                .firstOrNull { it.tag == selectedId }
            if (radio != null) binding.profileList.check(radio.id) else binding.profileList.clearCheck()
        } finally {
            bindingProfiles = false
        }
    }

    private fun showSelected() {
        val profile = profiles.firstOrNull { it.id == selectedId }
        if (profile == null) {
            binding.hostInput.setText("")
            binding.portInput.setText("")
            binding.userInput.setText("")
            binding.passwordInput.setText("")
            binding.bypassLan.isChecked = true
            return
        }
        binding.hostInput.setText(profile.host)
        binding.portInput.setText(if (profile.port in 1..65535) profile.port.toString() else "")
        binding.userInput.setText(profile.username)
        binding.passwordInput.setText(profile.password)
        binding.bypassLan.isChecked = profile.bypassLan
    }

    private fun importFromFile(): Boolean {
        val kept = KeptProxy.read(this) ?: return false
        if (kept.profiles.isEmpty()) return false
        replaceFromKept(kept)
        persist()
        return true
    }

    private fun showImported() {
        bindProfiles()
        showSelected()
        render(VpnRuntime.state)
        binding.statusText.text = getString(R.string.kept_restored)
    }

    private fun replaceFromKept(snapshot: KeptProfiles.Snapshot) {
        val passwordsById = profiles.associate { it.id to it.password }
        val passwordsByKey = profiles.groupBy { Triple(it.host, it.port, it.username) }
        val next = snapshot.profiles.map { item ->
            val id = item.id.ifBlank { UUID.randomUUID().toString() }
            val password = if (item.id.isNotBlank() && passwordsById.containsKey(item.id)) {
                passwordsById.getValue(item.id)
            } else {
                passwordsByKey[Triple(item.host, item.port, item.username)]?.singleOrNull()?.password.orEmpty()
            }
            VpnProfile(id, item.host, item.port, item.username, password, item.bypassLan)
        }
        profiles = next.toMutableList()
        selectedId = next.firstOrNull { it.id == snapshot.selectedId }?.id ?: next.firstOrNull()?.id
    }

    private fun validSnapshot(): KeptProfiles.Snapshot {
        val kept = ArrayList<KeptProfiles.Profile>()
        for (profile in profiles) {
            if (!isValid(profile)) continue
            kept.add(
                KeptProfiles.Profile(
                    id = profile.id,
                    host = profile.host,
                    port = profile.port,
                    username = profile.username.replace("\r", "").replace("\n", "").trim(),
                    bypassLan = profile.bypassLan,
                ),
            )
            if (kept.size == MAX_VPN_PROFILES) break
        }
        val selected = kept.firstOrNull { it.id == selectedId }?.id ?: kept.firstOrNull()?.id.orEmpty()
        return KeptProfiles.Snapshot(kept, selected)
    }

    /**
     * Пустой черновик до первой успешной записи не должен создавать хранилище
     * и затирать файл в «Загрузках»: новая установка ещё может его прочитать.
     */
    private fun persist(clearKeptIfEmpty: Boolean = false) {
        val kept = validSnapshot()
        val known = hadValidStored || Prefs.hasStoredProfiles(this)
        if (kept.profiles.isEmpty() && !known) return
        Prefs.saveStore(this, profiles.toList(), selectedId)
        if (kept.profiles.isNotEmpty()) {
            KeptProxy.write(this, kept)
            hadValidStored = true
            requestLegacyStorage()
        } else if (clearKeptIfEmpty && hadValidStored) {
            KeptProxy.write(this, kept)
            requestLegacyStorage()
        }
    }

    private fun requestLegacyStorage() {
        val missing = KeptProxy.missingLegacyPermissions(this)
        if (!askedLegacyStorage && missing.isNotEmpty()) {
            askedLegacyStorage = true
            legacyStorage.launch(missing)
        }
    }

    private fun launchAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            openKept.launch(arrayOf("*/*"))
            return
        }
        allFilesLaunching = true
        val appSettings = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        try {
            allFilesSettings.launch(appSettings)
        } catch (_: Exception) {
            try {
                allFilesSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                allFilesLaunching = false
                openKept.launch(arrayOf("*/*"))
            }
        }
    }
}
