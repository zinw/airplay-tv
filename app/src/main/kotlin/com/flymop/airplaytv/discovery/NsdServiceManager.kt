package com.flymop.airplaytv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null
    private var airplayRegistration: NsdManager.RegistrationListener? = null

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    /**
     * Publish `_raop._tcp`. Required for Screen Mirroring synced audio as well as
     * classic AirTunes; without it iOS may keep sound on the phone while video works.
     */
    fun registerRaop(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_raop._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }

        raopRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopRegistration)
    }

    /** Publish `_airplay._tcp` for Screen Mirroring / video AirPlay. */
    fun registerAirplay(serviceName: String, port: Int, txtRecords: Map<String, String>, retryCount: Int = 0) {
        val targetName = if (retryCount == 0) serviceName else when (retryCount) {
            1 -> if (serviceName.contains("AirPlay", ignoreCase = true)) "$serviceName 2" else "$serviceName AirPlay"
            2 -> "$serviceName (${retryCount + 1})"
            else -> "AirPlay TV"
        }

        val info = NsdServiceInfo().apply {
            this.serviceName = targetName
            serviceType = "_airplay._tcp"
            this.port = port
            txtRecords.forEach { (k, v) -> setAttribute(k, v) }
        }

        airplayRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay registered: ${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay registration failed ($code) for '${info.serviceName}'")
                if (retryCount < 3) {
                    Log.i(TAG, "Retrying AirPlay registration with fallback name...")
                    try {
                        registerAirplay(serviceName, port, txtRecords, retryCount + 1)
                    } catch (e: Exception) {
                        Log.e(TAG, "AirPlay retry failed", e)
                    }
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay unregister failed: $code")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayRegistration)
        } catch (e: Exception) {
            Log.e(TAG, "nsdManager.registerService threw exception for $targetName", e)
            if (retryCount < 3) {
                registerAirplay(serviceName, port, txtRecords, retryCount + 1)
            }
        }
    }

    fun unregisterAll() {
        raopRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            raopRegistration = null
        }
        airplayRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            airplayRegistration = null
        }
    }

    fun release() {
        unregisterAll()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
    }
}
