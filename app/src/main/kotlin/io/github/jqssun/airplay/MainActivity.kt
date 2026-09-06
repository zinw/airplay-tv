package io.github.jqssun.airplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airplay.tv.R
import com.airplay.tv.databinding.ActivityMainBinding
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.hudOverlay.visibility = View.GONE
        binding.settingsOverlay.visibility = View.GONE
        setupSurfaceView()
        setupSettings()
        startAndBindService()
    }

    private fun setupSurfaceView() {
        binding.surfaceView.holder.addCallback(this)
    }

    private fun setupSettings() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)

        // Populate initial values
        val currentName = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME
        binding.tvSettingDeviceNameVal.text = currentName
        binding.switchHud.isChecked = isHudVisible
        binding.switchLowLatency.isChecked = prefs.getBoolean(Prefs.LOW_LATENCY, Prefs.DEF_LOW_LATENCY)
        binding.switchH265.isChecked = prefs.getBoolean(Prefs.H265_ENABLED, Prefs.DEF_H265_ENABLED)
        binding.switchPin.isChecked = prefs.getBoolean(Prefs.REQUIRE_PIN, Prefs.DEF_REQUIRE_PIN)
        binding.tvSettingResolutionVal.text = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) ?: Prefs.DEF_RESOLUTION
        binding.tvSettingMaxFpsVal.text = "${prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)} FPS"

        // Open settings button on main screen
        binding.btnSettings.setOnClickListener {
            openSettings()
        }

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
            val newState = !binding.switchHud.isChecked
            binding.switchHud.isChecked = newState
            if (isHudVisible != newState) {
                toggleHud()
            }
        }

        // Row: Low-Latency Audio
        binding.rowSettingLowLatency.setOnClickListener {
            val newState = !binding.switchLowLatency.isChecked
            binding.switchLowLatency.isChecked = newState
            prefs.edit().putBoolean(Prefs.LOW_LATENCY, newState).apply()
        }

        // Row: H.265 Hardware Video
        binding.rowSettingH265.setOnClickListener {
            val newState = !binding.switchH265.isChecked
            binding.switchH265.isChecked = newState
            prefs.edit().putBoolean(Prefs.H265_ENABLED, newState).apply()
        }

        // Row: Resolution
        val resOptions = listOf(Prefs.AUTO, "1920x1080", "1280x720", "3840x2160")
        binding.rowSettingResolution.setOnClickListener {
            val currentRes = prefs.getString(Prefs.RESOLUTION, Prefs.DEF_RESOLUTION) ?: Prefs.DEF_RESOLUTION
            val nextIdx = (resOptions.indexOf(currentRes) + 1).let { if (it >= resOptions.size || it < 0) 0 else it }
            val newRes = resOptions[nextIdx]
            prefs.edit().putString(Prefs.RESOLUTION, newRes).apply()
            binding.tvSettingResolutionVal.text = newRes
        }

        // Row: Max FPS
        val fpsOptions = listOf(60, 30, 120)
        binding.rowSettingMaxFps.setOnClickListener {
            val currentFps = prefs.getInt(Prefs.MAX_FPS, Prefs.DEF_MAX_FPS)
            val nextIdx = (fpsOptions.indexOf(currentFps) + 1).let { if (it >= fpsOptions.size || it < 0) 0 else it }
            val newFps = fpsOptions[nextIdx]
            prefs.edit().putInt(Prefs.MAX_FPS, newFps).apply()
            binding.tvSettingMaxFpsVal.text = "$newFps FPS"
        }

        // Row: PIN Security
        binding.rowSettingPin.setOnClickListener {
            val newState = !binding.switchPin.isChecked
            binding.switchPin.isChecked = newState
            prefs.edit().putBoolean(Prefs.REQUIRE_PIN, newState).apply()
        }
    }

    private fun openSettings() {
        binding.settingsOverlay.visibility = View.VISIBLE
        binding.rowSettingDeviceName.requestFocus()
    }

    private fun closeSettings() {
        binding.settingsOverlay.visibility = View.GONE
        binding.btnSettings.requestFocus()
    }

    private fun showEditDeviceNameDialog() {
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val currentName = prefs.getString(Prefs.SERVER_NAME, Prefs.DEF_SERVER_NAME) ?: Prefs.DEF_SERVER_NAME

        val input = EditText(this).apply {
            setText(currentName)
            selectAll()
            setTextColor(resources.getColor(R.color.white, theme))
            setPadding(48, 24, 48, 24)
        }

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Edit Device Name")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString(Prefs.SERVER_NAME, newName).apply()
                    binding.tvSettingDeviceNameVal.text = newName
                    airPlayService?.let {
                        it.startServer(newName)
                        updateServerInfo(it)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
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
        val serverName = service.serverName.value
        val port = service.serverPort.value
        binding.tvServerName.text = serverName

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
                            binding.tvStatus.text = "Status: Broadcasting (mDNS active)"
                            binding.tvStatus.setTextColor(getColor(R.color.tv_accent))
                        }
                        ServerState.STOPPED -> {
                            binding.tvStatus.text = "Status: Stopped"
                            binding.tvStatus.setTextColor(getColor(R.color.tv_text_secondary))
                        }
                        ServerState.ERROR -> {
                            binding.tvStatus.text = "Status: Error"
                            binding.tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            service.activeRemotePin.collectLatest { pin ->
                runOnUiThread {
                    if (pin != null) {
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
            service.mirrorRunning.collectLatest { isMirroring ->
                runOnUiThread {
                    updateViewVisibility()
                }
            }
        }

        lifecycleScope.launch {
            service.videoLocation.collectLatest { location ->
                runOnUiThread {
                    updateViewVisibility()
                }
            }
        }

        lifecycleScope.launch {
            service.trackInfo.collectLatest { track ->
                runOnUiThread {
                    updateViewVisibility()
                    if (track != null) {
                        binding.tvTrackTitle.text = track.title ?: getString(R.string.unknown_track)
                        binding.tvTrackArtist.text = listOfNotNull(track.artist, track.album).joinToString(" - ")
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
        val hasMusic = service.trackInfo.value.title.isNotBlank() && !isMirroring && !hasHlsVideo

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
            }
            else -> {
                binding.hlsPlayerView.visibility = View.GONE
                binding.videoContainer.visibility = View.GONE
                binding.surfaceView.visibility = View.GONE
                binding.musicContainer.visibility = View.GONE
                binding.ambientContainer.visibility = View.VISIBLE
                binding.visualizerView.setPlaying(false)
            }
        }
    }

    private fun toggleHud() {
        isHudVisible = !isHudVisible
        binding.hudOverlay.visibility = if (isHudVisible) View.VISIBLE else View.GONE
        binding.switchHud.isChecked = isHudVisible
        if (isHudVisible) {
            mainHandler.removeCallbacks(hudUpdateRunnable)
            mainHandler.post(hudUpdateRunnable)
        } else {
            mainHandler.removeCallbacks(hudUpdateRunnable)
        }
    }

    private fun updateHud() {
        val service = airPlayService ?: return
        val vRenderer = service.videoRenderer
        val aRenderer = service.audioRenderer
        val audioDbg = aRenderer.audioDebug()

        val videoStats = "${vRenderer.codecName} | ${vRenderer.fps} fps | ${"%.1f".format((vRenderer.bitrateBps / 1000.0) / 1000.0)} Mbps | Drops: ${vRenderer.droppedFrames}"
        val audioStats = if (audioDbg != null) {
            "Audio: ${aRenderer.codecLabel} | Cushion: ${audioDbg.tunedCushionMs}ms | XRuns: ${audioDbg.xrun} | Underruns: ${audioDbg.underruns}"
        } else {
            "Audio: ${aRenderer.codecLabel}"
        }

        binding.tvHudStats.text = "$videoStats\n$audioStats"
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

        when (keyCode) {
            // Directional keys: DO NOT INTERCEPT, let Android TV focus engine navigate between views
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                return super.onKeyDown(keyCode, event)
            }

            // Center / Enter: When UI views (like Settings) are visible, let standard click event fire
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (isSettingsOpen || isAmbientOpen) {
                    return super.onKeyDown(keyCode, event)
                }
                // During active media playback without UI open, act as play/pause
                if (service?.playing?.value == true) {
                    service?.dacpPlayer?.pause()
                } else {
                    service?.dacpPlayer?.play()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (service?.playing?.value == true) {
                    service?.dacpPlayer?.pause()
                } else {
                    service?.dacpPlayer?.play()
                }
                return true
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                service?.dacpController?.nextItem()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                service?.dacpController?.prevItem()
                return true
            }

            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_PROG_BLUE -> {
                toggleHud()
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                if (isSettingsOpen) {
                    closeSettings()
                    return true
                }
                if (isHudVisible) {
                    toggleHud()
                    return true
                }
                if (service?.mirrorRunning?.value == true || service?.videoLocation?.value != null) {
                    service.dacpPlayer?.stop()
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
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
