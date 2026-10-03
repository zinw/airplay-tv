/*
 * AirPlay TV - Open-source AirPlay receiver for Android TV
 *
 * Based on android-airplay-server by jqssun (GPLv3) and UxPlay (GPLv3).
 * Modified and optimized by flymop (2026) for Android TV Leanback experience.
 *
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 */

package com.flymop.airplaytv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.flymop.airplaytv.R
import com.flymop.airplaytv.databinding.ActivityMainBinding
import com.flymop.airplaytv.Prefs
import com.flymop.airplaytv.diag.DiagLogShipper
import com.flymop.airplaytv.net.WifiLinkMetrics
import com.flymop.airplaytv.service.AirPlayService
import com.flymop.airplaytv.service.AirPlayService.ServerState
import com.flymop.airplaytv.ui.MirrorHudFormatter
import com.flymop.airplaytv.update.ApkInstaller
import com.flymop.airplaytv.update.AppUpdateChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var binding: ActivityMainBinding
    private var airPlayService: AirPlayService? = null
    private var isBound = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isHudVisible = false
    private var currentPort = 7000
    private var pendingInstallUpdate: AppUpdateChecker.AvailableUpdate? = null
    private var updateDownloadJob: Job? = null
    private var downloadProgressDialog: AlertDialog? = null
    private var lastAvailableUpdate: AppUpdateChecker.AvailableUpdate? = null

    private val diagShipListener = DiagLogShipper.OutcomeListener { outcome ->
        if (!::binding.isInitialized || isFinishing || isDestroyed) return@OutcomeListener
        binding.tvDiagShipStatus.text = formatDiagShipOutcome(outcome)
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val update = pendingInstallUpdate
        pendingInstallUpdate = null
        if (update == null) return@registerForActivityResult
        if (ApkInstaller.canInstallPackages(this)) {
            startDownloadAndInstall(update)
        } else {
            Toast.makeText(this, R.string.update_install_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AirPlayService.LocalBinder
            airPlayService = localBinder?.service
            isBound = true
            Log.i(TAG, "Connected to AirPlayService")
            onServiceBound()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            airPlayService = null
            isBound = false
            Log.i(TAG, "Disconnected from AirPlayService")
        }
    }

    private val hudUpdateRunnable = object : Runnable {
        override fun run() {
            updateHud()
            if (isHudVisible) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        /** One GitHub Releases check per process (cold start). */
        @Volatile private var updateCheckStartedThisProcess = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyKeepScreenOn()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        isHudVisible = prefs.getBoolean(Prefs.DEBUG_ENABLED, Prefs.DEF_DEBUG_ENABLED)
        binding.hudOverlay.visibility = if (isHudVisible) View.VISIBLE else View.GONE
        binding.settingsOverlay.visibility = View.GONE
        if (isHudVisible) {
            mainHandler.post(hudUpdateRunnable)
        }

        setupSurfaceView()
        setupSettings()
        refreshHomeMeta()
        bindVersionLabels()
        startAndBindService()
        maybeCheckForUpdate()
    }

    override fun onStart() {
        super.onStart()
        DiagLogShipper.addOutcomeListener(diagShipListener)
        binding.tvDiagShipStatus.setText(R.string.diag_ship_sending)
        val (name, code) = currentVersion()
        // Always beacon on activity start — re-opening while service is RUNNING skips Service.onCreate.
        DiagLogShipper.sendBeacon(name, code.toInt(), from = "activity")
    }

    override fun onStop() {
        DiagLogShipper.removeOutcomeListener(diagShipListener)
        super.onStop()
    }

    private fun formatDiagShipOutcome(outcome: DiagLogShipper.Outcome): String {
        if (outcome.ok) {
            val code = outcome.httpStatus ?: 0
            return getString(R.string.diag_ship_ok, code)
        }
        return when (outcome.errorKind) {
            "timeout" -> getString(R.string.diag_ship_fail_timeout)
            "dns" -> getString(R.string.diag_ship_fail_dns)
            "connect" -> getString(R.string.diag_ship_fail_connect)
            "ssl" -> getString(R.string.diag_ship_fail_ssl)
            "http" -> getString(R.string.diag_ship_fail_http, outcome.httpStatus ?: 0)
            else -> getString(R.string.diag_ship_fail_other)
        }
    }

    /** Soft fail: offline / rate-limit / parse errors never block the home screen. */
    private fun maybeCheckForUpdate() {
        if (updateCheckStartedThisProcess) return
        updateCheckStartedThisProcess = true
        binding.tvUpdateStatus.setText(R.string.home_update_checking)
        lifecycleScope.launch {
            val versionLabel = currentVersionLabel()
            val update = try {
                AppUpdateChecker.check(this@MainActivity)
            } catch (e: Exception) {
                Log.i(TAG, "Update check skipped: ${e.message}")
                null
            }
            if (isFinishing || isDestroyed) return@launch
            if (update != null) {
                lastAvailableUpdate = update
                binding.tvUpdateStatus.text = getString(R.string.home_update_available, update.versionLabel)
                showUpdateAvailableDialog(update)
            } else {
                binding.tvUpdateStatus.text = getString(R.string.home_update_current, versionLabel)
            }
        }
    }

    private fun currentVersion(): Pair<String, Long> {
        return try {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pkg.versionCode.toLong()
            }
            (pkg.versionName ?: "1.0.0") to code
        } catch (_: Exception) {
            "1.0.0" to 0L
        }
    }

    /** Short form used in update status lines, e.g. `1.0.3 (4)`. */
    private fun currentVersionLabel(): String {
        val (name, code) = currentVersion()
        return getString(R.string.app_version_short, name, code)
    }

    /** Localized label for home chip / settings header, e.g. `Version 1.0.3 (4)`. */
    private fun currentVersionDisplay(): String {
        val (name, code) = currentVersion()
        return getString(R.string.app_version_label, name, code)
    }

    private fun bindVersionLabels() {
        val label = currentVersionDisplay()
        binding.tvAppVersion.text = label
        binding.tvSettingsVersion.text = label
    }

    private fun refreshHomeMeta() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        binding.btnPinHome.setText(
            if (Prefs.isRequirePin(prefs)) R.string.home_pin_on else R.string.home_pin_off
        )
        binding.btnLanguageHome.setText(LocaleHelper.displayNameRes(LocaleHelper.getLanguage(prefs)))
        bindVersionLabels()
        if (binding.tvUpdateStatus.text.isNullOrBlank()) {
            binding.tvUpdateStatus.text = getString(R.string.home_update_unknown, currentVersionLabel())
        }
    }

    private fun showUpdateAvailableDialog(update: AppUpdateChecker.AvailableUpdate) {
        val currentLabel = currentVersionLabel()

        AlertDialog.Builder(this, R.style.Theme_AirPlayTV_Dialog)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_message, update.versionLabel, currentLabel))
            .setPositiveButton(R.string.update_confirm) { _, _ ->
                confirmUpdateInstall(update)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun confirmUpdateInstall(update: AppUpdateChecker.AvailableUpdate) {
        if (!ApkInstaller.canInstallPackages(this)) {
            pendingInstallUpdate = update
            Toast.makeText(this, R.string.update_install_permission_required, Toast.LENGTH_LONG).show()
            installPermissionLauncher.launch(ApkInstaller.createUnknownSourcesIntent(this))
            return
        }
        startDownloadAndInstall(update)
    }

    private fun startDownloadAndInstall(update: AppUpdateChecker.AvailableUpdate) {
        updateDownloadJob?.cancel()
        downloadProgressDialog?.dismiss()
        lastAvailableUpdate = update

        val progressDialog = AlertDialog.Builder(this, R.style.Theme_AirPlayTV_Dialog)
            .setTitle(R.string.update_available_title)
            .setMessage(R.string.update_downloading)
            .setCancelable(true)
            .setNegativeButton(R.string.update_cancel) { _, _ ->
                updateDownloadJob?.cancel()
            }
            .setOnCancelListener {
                updateDownloadJob?.cancel()
            }
            .create()
        downloadProgressDialog = progressDialog
        progressDialog.show()

        updateDownloadJob = lifecycleScope.launch {
            try {
                val apk = ApkInstaller.download(
                    context = this@MainActivity,
                    url = update.apkDownloadUrl,
                    fileName = update.apkFileName,
                    expectedSizeBytes = update.apkSizeBytes,
                ) { progress ->
                    val total = progress.total
                    runOnUiThread {
                        if (!progressDialog.isShowing) return@runOnUiThread
                        if (total > 0L) {
                            val pct = ((progress.downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            progressDialog.setMessage(
                                getString(
                                    R.string.update_download_progress_via,
                                    progress.sourceLabel,
                                    pct,
                                )
                            )
                        } else {
                            progressDialog.setMessage(
                                getString(
                                    R.string.update_download_retrying,
                                    progress.attempt,
                                )
                            )
                        }
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                progressDialog.setMessage(getString(R.string.update_installing))
                ApkInstaller.install(this@MainActivity, apk)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (downloadProgressDialog === progressDialog) downloadProgressDialog = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Update download cancelled")
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (downloadProgressDialog === progressDialog) downloadProgressDialog = null
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download/install failed", e)
                if (progressDialog.isShowing) progressDialog.dismiss()
                if (downloadProgressDialog === progressDialog) downloadProgressDialog = null
                if (!isFinishing && !isDestroyed) {
                    showUpdateFailedDialog(update, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun showUpdateFailedDialog(update: AppUpdateChecker.AvailableUpdate, reason: String) {
        AlertDialog.Builder(this, R.style.Theme_AirPlayTV_Dialog)
            .setTitle(R.string.update_failed_title)
            .setMessage(getString(R.string.update_failed_message, reason))
            .setPositiveButton(R.string.update_retry) { _, _ ->
                confirmUpdateInstall(update)
            }
            .setNegativeButton(R.string.update_cancel, null)
            .show()
    }

    private fun setupSurfaceView() {
        binding.surfaceView.holder.addCallback(this)
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        // Populate initial values
        bindSettingsFromPrefs(prefs)

        // Home D-pad action row (above the fold)
        binding.btnSettings.setOnClickListener { openSettings() }
        binding.btnLanguageHome.setOnClickListener { cycleLanguageFromHome() }
        binding.btnPinHome.setOnClickListener { togglePinFromHome() }
        binding.btnSettings.requestFocus()

        // Close button inside settings
        binding.btnCloseSettings.setOnClickListener {
            closeSettings()
        }

        // Row: Device Name
        binding.rowSettingDeviceName.setOnClickListener {
            showEditDeviceNameDialog()
        }

        // Row: HUD Overlay
        binding.rowSettingHud.setOnClickListener {
            toggleHud()
        }

        // Row: H.265 Hardware Video
        binding.rowSettingH265.setOnClickListener {
            val newState = !binding.switchH265.isChecked
            binding.switchH265.isChecked = newState
            prefs.edit().putBoolean(Prefs.H265_ENABLED, newState).apply()
            restartServerWithFeedback()
        }

        // Row: Resolution
        val resOptions = listOf(Prefs.AUTO, "1920x1080", "1280x720", "3840x2160")
        binding.rowSettingResolution.setOnClickListener {
            val currentRes = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) ?: Prefs.DEF_RESOLUTION
            val nextIdx = (resOptions.indexOf(currentRes) + 1).let { if (it >= resOptions.size || it < 0) 0 else it }
            val newRes = resOptions[nextIdx]
            prefs.edit().putString(Prefs.RESOLUTION, newRes).apply()
            binding.tvSettingResolutionVal.text = resolutionLabel(newRes)
            restartServerWithFeedback()
        }

        // Row: Max FPS (ascending: 30 → 60 → 120)
        val fpsOptions = listOf(30, 60, 120)
        binding.rowSettingMaxFps.setOnClickListener {
            val currentFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
            val nextIdx = (fpsOptions.indexOf(currentFps) + 1).let { if (it >= fpsOptions.size || it < 0) 0 else it }
            val newFps = fpsOptions[nextIdx]
            prefs.edit().putInt(Prefs.MAX_FPS, newFps).apply()
            binding.tvSettingMaxFpsVal.text = getString(R.string.fps_value, newFps)
            restartServerWithFeedback()
        }

        // Row: PIN Security
        binding.rowSettingPin.setOnClickListener {
            togglePinFromHome()
        }

        // Row: Overscan
        binding.rowSettingOverscan.setOnClickListener {
            val newState = !binding.switchOverscan.isChecked
            binding.switchOverscan.isChecked = newState
            prefs.edit().putBoolean(Prefs.OVERSCANNED, newState).apply()
            restartServerWithFeedback()
        }

        // Row: Allow new connections
        binding.rowSettingAllowNewConn.setOnClickListener {
            val newState = !binding.switchAllowNewConn.isChecked
            binding.switchAllowNewConn.isChecked = newState
            prefs.edit().putBoolean(Prefs.ALLOW_NEW_CONN, newState).apply()
            restartServerWithFeedback()
        }

        // Row: Advertise audio (mirror-audio style toggle from iMirror/PhairPlay)
        binding.rowSettingAdvertiseAudio.setOnClickListener {
            val newState = !binding.switchAdvertiseAudio.isChecked
            binding.switchAdvertiseAudio.isChecked = newState
            prefs.edit().putBoolean(Prefs.ADVERTISE_AUDIO, newState).apply()
            restartServerWithFeedback()
        }

        // Row: Audio stability (adaptive cushion step — hot-applied via audioConfigFlow)
        binding.rowSettingAudioStability.setOnClickListener {
            val current = prefs.getInt(Prefs.AUDIO_ADAPTIVE_STEP, Prefs.DEF_AUDIO_ADAPTIVE_STEP)
            val next = Prefs.nextAudioAdaptiveStep(current)
            prefs.edit().putInt(Prefs.AUDIO_ADAPTIVE_STEP, next).apply()
            binding.tvSettingAudioStabilityVal.setText(audioStabilityLabelRes(next))
        }

        // Row: Boot auto-start (no server restart needed)
        binding.rowSettingBootAutoStart.setOnClickListener {
            val newState = !binding.switchBootAutoStart.isChecked
            binding.switchBootAutoStart.isChecked = newState
            prefs.edit().putBoolean(Prefs.BOOT_AUTO_START, newState).apply()
        }

        // Row: Language (System <-> 简体中文 <-> English)
        binding.rowSettingLanguage.setOnClickListener {
            cycleLanguageFromHome()
        }
    }

    private fun bindSettingsFromPrefs(prefs: android.content.SharedPreferences) {
        binding.tvSettingDeviceNameVal.text = Prefs.getServerName(prefs)
        binding.switchHud.isChecked = isHudVisible
        binding.switchH265.isChecked = prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED)
        binding.switchPin.isChecked = Prefs.isRequirePin(prefs)
        binding.switchOverscan.isChecked = prefs.getBoolean(Prefs.OVERSCANNED, Prefs.DEF_OVERSCANNED)
        binding.switchAllowNewConn.isChecked = prefs.getBoolean(Prefs.ALLOW_NEW_CONN, Prefs.DEF_ALLOW_NEW_CONN)
        binding.switchAdvertiseAudio.isChecked = prefs.getBoolean(Prefs.ADVERTISE_AUDIO, Prefs.DEF_ADVERTISE_AUDIO)
        binding.switchBootAutoStart.isChecked = prefs.getBoolean(Prefs.BOOT_AUTO_START, Prefs.DEF_BOOT_AUTO_START)
        binding.tvSettingResolutionVal.text = resolutionLabel(
            prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) ?: Prefs.DEF_RESOLUTION
        )
        binding.tvSettingMaxFpsVal.text = getString(
            R.string.fps_value,
            prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
        )
        binding.tvSettingAudioStabilityVal.setText(
            audioStabilityLabelRes(prefs.getInt(Prefs.AUDIO_ADAPTIVE_STEP, Prefs.DEF_AUDIO_ADAPTIVE_STEP))
        )
        binding.tvSettingLanguageVal.setText(LocaleHelper.displayNameRes(LocaleHelper.getLanguage(prefs)))
    }

    private fun audioStabilityLabelRes(step: Int): Int = when (Prefs.audioStabilityBucket(step)) {
        0 -> R.string.audio_stability_low
        2 -> R.string.audio_stability_stable
        else -> R.string.audio_stability_balanced
    }

    private fun applyKeepScreenOn() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEEP_SCREEN_ON, Prefs.DEF_KEEP_SCREEN_ON)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Restart RAOP after a setting that affects advertisement / decode path. */
    private fun restartServerWithFeedback() {
        airPlayService?.restartServer()
        Toast.makeText(this, R.string.settings_applied_reconnect, Toast.LENGTH_SHORT).show()
    }

    private fun cycleLanguageFromHome() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val next = LocaleHelper.cycleLanguage(prefs)
        binding.tvSettingLanguageVal.setText(LocaleHelper.displayNameRes(next))
        binding.btnLanguageHome.setText(LocaleHelper.displayNameRes(next))
        refreshHomeMeta()
    }

    private fun togglePinFromHome() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val newState = !Prefs.isRequirePin(prefs)
        binding.switchPin.isChecked = newState
        prefs.edit().putBoolean(Prefs.REQUIRE_PIN, newState).apply()
        refreshHomeMeta()
        restartServerWithFeedback()
    }

    private fun resolutionLabel(res: String): String {
        return if (res == Prefs.AUTO) getString(R.string.resolution_auto) else res
    }

    private fun openSettings() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        bindSettingsFromPrefs(prefs)
        bindVersionLabels()

        binding.settingsOverlay.visibility = View.VISIBLE
        binding.rowSettingDeviceName.requestFocus()
    }

    private fun closeSettings() {
        binding.settingsOverlay.visibility = View.GONE
        binding.btnSettings.requestFocus()
    }

    private fun showEditDeviceNameDialog() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val currentName = Prefs.getServerName(prefs)

        val input = EditText(this).apply {
            setText(currentName)
            selectAll()
            setTextColor(resources.getColor(R.color.white, theme))
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this, R.style.Theme_AirPlayTV_Dialog)
            .setTitle(R.string.edit_device_name_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString(Prefs.SERVER_NAME, newName).apply()
                    binding.tvSettingDeviceNameVal.text = newName
                    binding.tvServerName.text = newName
                    airPlayService?.let {
                        it.restartServer(newName)
                        updateServerInfo(it)
                    }
                    Toast.makeText(this, R.string.settings_applied_reconnect, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun startAndBindService() {
        val intent = Intent(this, AirPlayService::class.java).apply {
            action = AirPlayService.ACTION_START_SERVER
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun updateServerInfo(service: AirPlayService) {
        val serverName = service.serverName.value.ifBlank {
            Prefs.getServerName(getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE))
        }
        val port = service.serverPort.value
        binding.tvServerName.text = serverName
        binding.tvSettingDeviceNameVal.text = serverName

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        binding.tvIpAddress.text = "$ip:$port"
    }

    private fun onServiceBound() {
        val service = airPlayService ?: return
        if (service.serverState.value != ServerState.RUNNING) {
            service.startServer(service.serverName.value)
        }

        updateServerInfo(service)
        updateViewVisibility()

        // Attach surface to video renderer if already created
        binding.surfaceView.holder.surface?.let { surface ->
            if (surface.isValid) {
                service.videoRenderer.setSurface(surface)
            }
        }

        // Attach player view to ExoPlayer
        service.airPlayVideoPlayer.attachPlayerView(binding.hlsPlayerView)

        // Observe flows
        lifecycleScope.launch {
            service.serverPort.collectLatest {
                runOnUiThread { updateServerInfo(service) }
            }
        }

        lifecycleScope.launch {
            service.serverName.collectLatest {
                runOnUiThread { updateServerInfo(service) }
            }
        }
        lifecycleScope.launch {
            service.serverState.collectLatest { state ->
                runOnUiThread {
                    when (state) {
                        ServerState.RUNNING -> {
                            binding.tvStatus.text = getString(R.string.status_broadcasting)
                            binding.tvStatus.setTextColor(getColor(R.color.tv_status_green))
                            binding.tvStatus.setBackgroundResource(R.drawable.bg_status_capsule)
                            // rename / settings restartServer does not recreate SurfaceView —
                            // reattach so VideoPipeline gets a live display after GL reset
                            binding.surfaceView.holder.surface?.takeIf { it.isValid }?.let {
                                service.videoRenderer.setSurface(it)
                            }
                        }
                        ServerState.STOPPED -> {
                            binding.tvStatus.text = getString(R.string.status_stopped)
                            binding.tvStatus.setTextColor(getColor(R.color.tv_text_secondary))
                            binding.tvStatus.setBackgroundResource(R.drawable.bg_chip_neutral)
                        }
                        ServerState.ERROR -> {
                            binding.tvStatus.text = getString(R.string.status_error)
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                            binding.tvStatus.setBackgroundResource(R.drawable.bg_chip_neutral)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.activeRemotePin.collectLatest { pin ->
                runOnUiThread {
                    val show = Prefs.shouldShowPinOverlay(Prefs.isRequirePin(
                        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    ), pin)
                    if (show) {
                        binding.tvPinCode.text = pin
                        binding.pinOverlay.visibility = View.VISIBLE
                    } else {
                        binding.pinOverlay.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.videoAspect.collectLatest { aspect ->
                runOnUiThread {
                    binding.videoContainer.setAspectRatio(aspect)
                }
            }
        }

        lifecycleScope.launch {
            service.mirrorRunning.collectLatest {
                runOnUiThread {
                    updateViewVisibility()
                }
            }
        }

        lifecycleScope.launch {
            service.videoLocation.collectLatest {
                runOnUiThread {
                    updateViewVisibility()
                }
            }
        }

        lifecycleScope.launch {
            service.audioOnly.collectLatest {
                runOnUiThread {
                    updateViewVisibility()
                }
            }
        }

        lifecycleScope.launch {
            service.trackInfo.collectLatest { track ->
                runOnUiThread {
                    updateViewVisibility()
                    if (track.title.isNotBlank()) {
                        binding.tvTrackTitle.text = track.title
                        binding.tvTrackArtist.text = listOfNotNull(track.artist.takeIf { it.isNotBlank() }, track.album.takeIf { it.isNotBlank() }).joinToString(" - ")
                    } else {
                        binding.tvTrackTitle.text = getString(R.string.airplay_audio_title)
                        binding.tvTrackArtist.text = getString(R.string.airplay_audio_subtitle)
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.coverArt.collectLatest { bytes ->
                runOnUiThread {
                    if (bytes != null && bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        binding.ivCoverArt.setImageBitmap(bmp)
                    } else {
                        binding.ivCoverArt.setImageResource(R.drawable.bg_card)
                    }
                }
            }
        }
    }

    private fun updateViewVisibility() {
        val service = airPlayService ?: return
        val isMirroring = service.mirrorRunning.value
        val hasHlsVideo = service.videoLocation.value != null
        val hasMusic = (service.audioOnly.value || service.trackInfo.value.title.isNotBlank()) && !isMirroring && !hasHlsVideo

        when {
            hasHlsVideo -> {
                binding.ambientContainer.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.surfaceView.visibility = View.GONE
                binding.musicContainer.visibility = View.GONE
                binding.hlsPlayerView.visibility = View.VISIBLE
                binding.visualizerView.setPlaying(false)
            }
            isMirroring -> {
                binding.ambientContainer.visibility = View.GONE
                binding.hlsPlayerView.visibility = View.GONE
                binding.musicContainer.visibility = View.GONE
                binding.videoContainer.visibility = View.VISIBLE
                binding.surfaceView.visibility = View.VISIBLE
                binding.visualizerView.setPlaying(false)
            }
            hasMusic -> {
                binding.ambientContainer.visibility = View.GONE
                binding.hlsPlayerView.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.surfaceView.visibility = View.GONE
                binding.musicContainer.visibility = View.VISIBLE
                binding.visualizerView.setPlaying(true)
                if (service.trackInfo.value.title.isBlank()) {
                    binding.tvTrackTitle.text = getString(R.string.airplay_audio_title)
                    binding.tvTrackArtist.text = getString(R.string.airplay_audio_subtitle)
                }
            }
            else -> {
                binding.hlsPlayerView.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.surfaceView.visibility = View.GONE
                binding.musicContainer.visibility = View.GONE
                binding.ambientContainer.visibility = View.VISIBLE
                binding.visualizerView.setPlaying(false)
                if (!binding.btnSettings.hasFocus() && binding.settingsOverlay.visibility != View.VISIBLE) {
                    binding.btnSettings.requestFocus()
                }
            }
        }
    }

    private fun toggleHud() {
        isHudVisible = !isHudVisible
        binding.hudOverlay.visibility = if (isHudVisible) View.VISIBLE else View.GONE
        binding.switchHud.isChecked = isHudVisible
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(Prefs.DEBUG_ENABLED, isHudVisible)
            .apply()
        if (isHudVisible) {
            mainHandler.removeCallbacks(hudUpdateRunnable)
            mainHandler.post(hudUpdateRunnable)
        } else {
            mainHandler.removeCallbacks(hudUpdateRunnable)
        }
    }

    private fun updateHud() {
        val service = airPlayService
        val wifi = WifiLinkMetrics.snapshot(this)
        binding.hudSignalBars.level = wifi.signalBars ?: 0

        if (service == null) {
            binding.tvHudStats.text = MirrorHudFormatter.formatLine(
                mediaCodecName = null,
                receivedFps = null,
                decodedFps = null,
                width = 0,
                height = 0,
                wifiBand = wifi.bandLabel,
            )
            return
        }

        val vRenderer = service.videoRenderer
        val mirroring = service.mirrorRunning.value
        val received = if (mirroring) vRenderer.receivedFps else null
        val decoded = if (mirroring) vRenderer.decodedFps else null
        val w = if (mirroring) vRenderer.streamWidth else 0
        val h = if (mirroring) vRenderer.streamHeight else 0
        // Prefer live stream size from the renderer; fall back to service resolution string.
        val (resW, resH) = if (w > 0 && h > 0) {
            w to h
        } else if (mirroring) {
            parseResolution(service.videoResolution.value)
        } else {
            0 to 0
        }

        binding.tvHudStats.text = MirrorHudFormatter.formatLine(
            mediaCodecName = if (mirroring) vRenderer.mediaCodecName.ifBlank { null } else null,
            receivedFps = received,
            decodedFps = decoded,
            width = resW,
            height = resH,
            wifiBand = wifi.bandLabel,
        )
    }

    private fun parseResolution(raw: String): Pair<Int, Int> {
        val parts = raw.split('x', '×', limit = 2)
        if (parts.size != 2) return 0 to 0
        val w = parts[0].trim().toIntOrNull() ?: return 0 to 0
        val h = parts[1].trim().toIntOrNull() ?: return 0 to 0
        return w to h
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        airPlayService?.videoRenderer?.setSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        airPlayService?.videoRenderer?.setSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        airPlayService?.videoRenderer?.clearSurface(holder.surface)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val service = airPlayService
        val isSettingsOpen = binding.settingsOverlay.visibility == View.VISIBLE
        val isAmbientOpen = binding.ambientContainer.visibility == View.VISIBLE
        val isMirroring = service?.mirrorRunning?.value == true
        val hasHlsVideo = service?.videoLocation?.value != null
        val hasMusic = (service?.audioOnly?.value == true || service?.trackInfo?.value?.title?.isNotBlank() == true) && !isMirroring && !hasHlsVideo
        val isHlsControllerVisible = hasHlsVideo && binding.hlsPlayerView.isControllerFullyVisible

        when (keyCode) {
            // Directional keys:
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (hasHlsVideo && !isHlsControllerVisible) {
                    binding.hlsPlayerView.showController()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isSettingsOpen || isAmbientOpen) {
                    return super.onKeyDown(keyCode, event)
                }
                if (hasHlsVideo) {
                    if (isHlsControllerVisible) {
                        return super.onKeyDown(keyCode, event)
                    }
                    service?.seekVideoBy(-AirPlayService.VIDEO_SEEK_STEP_MS)
                    binding.hlsPlayerView.showController()
                    return true
                }
                if (hasMusic) {
                    service?.dacpController?.prevItem()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isSettingsOpen || isAmbientOpen) {
                    return super.onKeyDown(keyCode, event)
                }
                if (hasHlsVideo) {
                    if (isHlsControllerVisible) {
                        return super.onKeyDown(keyCode, event)
                    }
                    service?.seekVideoBy(AirPlayService.VIDEO_SEEK_STEP_MS)
                    binding.hlsPlayerView.showController()
                    return true
                }
                if (hasMusic) {
                    service?.dacpController?.nextItem()
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            // Center / Enter: When UI views (like Settings, Ambient, or HLS OSD) are visible, let standard click event fire
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (isSettingsOpen || isAmbientOpen || isHlsControllerVisible) {
                    return super.onKeyDown(keyCode, event)
                }
                if (hasHlsVideo) {
                    service?.toggleVideoPlayPause()
                    return true
                }
                if (hasMusic || isMirroring) {
                    if (service?.playing?.value == true) {
                        service.dacpPlayer.pause()
                    } else {
                        service?.dacpPlayer?.play()
                    }
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (hasHlsVideo) {
                    service?.toggleVideoPlayPause()
                    return true
                }
                if (service?.playing?.value == true) {
                    service.dacpPlayer.pause()
                } else {
                    service?.dacpPlayer?.play()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                if (hasHlsVideo) {
                    service?.setVideoPlaying(true)
                    return true
                }
                service?.dacpPlayer?.play()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (hasHlsVideo) {
                    service?.setVideoPlaying(false)
                    return true
                }
                service?.dacpPlayer?.pause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_STOP -> {
                service?.disconnectSessions()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (hasHlsVideo) {
                    service?.seekVideoBy(AirPlayService.VIDEO_SEEK_STEP_MS)
                    binding.hlsPlayerView.showController()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (hasHlsVideo) {
                    service?.seekVideoBy(-AirPlayService.VIDEO_SEEK_STEP_MS)
                    binding.hlsPlayerView.showController()
                    return true
                }
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (hasHlsVideo) {
                    binding.hlsPlayerView.player?.seekToNextMediaItem()
                    return true
                }
                service?.dacpController?.nextItem()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (hasHlsVideo) {
                    binding.hlsPlayerView.player?.seekToPreviousMediaItem()
                    return true
                }
                service?.dacpController?.prevItem()
                return true
            }

            KeyEvent.KEYCODE_MENU -> {
                if (isSettingsOpen) {
                    closeSettings()
                    return true
                }
                if (isAmbientOpen) {
                    openSettings()
                    return true
                }
                toggleHud()
                return true
            }

            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> {
                toggleHud()
                return true
            }

            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (isSettingsOpen) {
                    closeSettings()
                    return true
                }
                if (isHudVisible) {
                    toggleHud()
                    return true
                }
                if (hasHlsVideo && isHlsControllerVisible) {
                    binding.hlsPlayerView.hideController()
                    return true
                }
                // When in Screen Mirroring, HLS Video, or Audio, Back button disconnects the session
                // returning to ambient screen (matching Apple TV operate logic)
                if (isMirroring || hasHlsVideo || hasMusic) {
                    service?.disconnectSessions()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining local IP", e)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(hudUpdateRunnable)
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        downloadProgressDialog?.dismiss()
        downloadProgressDialog = null
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
