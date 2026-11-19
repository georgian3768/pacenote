package com.pocketmechanic.spike

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.app.Activity
import android.app.PendingIntent
import android.provider.Settings

/**
 * Wi-Fi plumbing for the spike.
 *
 * The SSID and passphrase are placeholders for the AP under test; they are
 * configuration, not secrets, and belong in the setup screen in Phase 1.
 */
object WifiController {
    const val ESP_SSID = "A0RETSSID"
    const val ESP_PASSPHRASE = "aBigSecret"
    const val ESP_HOST = "192.168.4.1"
    const val ESP_SUBNET_PREFIX = "192.168.4."

    // Set by the network callback; the SSE client reads it to bind its sockets.
    @Volatile
    var espNetwork: Network? = null

    /**
     * Last capabilities line written to the event log. HyperOS re-delivers
     * onCapabilitiesChanged every few seconds for the connected AP, which used
     * to fill the log with identical `caps ...` lines (~7/min measured
     * 2026-11-10) and push real `[cue]` lines out of the ring.
     */
    private var lastCapsLog: String? = null
        private set

    fun suggest(context: Context): String {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: return "WifiManager unavailable".also { EventLog.add("wifi", it) }

        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(ESP_SSID)
            .setWpa2Passphrase(ESP_PASSPHRASE)
            .build()

        val status = wifi.addNetworkSuggestions(listOf(suggestion))
        val text = when (status) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "approved"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "duplicate"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> "exceeds max"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> "app disallowed"
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL -> "internal error"
            else -> "status $status"
        }
        EventLog.add("wifi", "addNetworkSuggestions($ESP_SSID) -> $text")
        return text
    }

    /**
     * The Android 11+ system dialog that adds the ESP32 to the user's **saved**
     * network list.
     *
     * A suggestion is not a saved network: the platform may keep cellular as
     * the default (the AP has no internet) and is not obliged to return to it.
     * A saved network is what it reconnects on its own when the AP reappears.
     * The dialog needs a user tap; the platform then connects to the network it
     * just saved. Returns null on API 29, where the suggestion is the only
     * public path.
     */
    fun saveNetworkIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val network = WifiNetworkSuggestion.Builder()
            .setSsid(ESP_SSID)
            .setWpa2Passphrase(ESP_PASSPHRASE)
            .build()
        return Intent(Settings.ACTION_WIFI_ADD_NETWORKS).apply {
            putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(network))
        }
    }

    /**
     * True when the add-network dialog saved the ESP32, or found it already
     * saved. Both outcomes mean the network is in the platform's list now.
     */
    fun saveSucceeded(resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        val codes = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
            ?: return false
        return codes.any {
            it == Settings.ADD_WIFI_RESULT_SUCCESS ||
                it == Settings.ADD_WIFI_RESULT_ALREADY_EXISTS
        }
    }

    /** One line for both menu surfaces, so they cannot word "saved" differently. */
    fun describe(saved: Boolean, online: Boolean): String = when {
        online && saved -> "Saved · connected now"
        online -> "Connected now · tap to save for auto-reconnect"
        saved -> "Saved · Android rejoins when the ESP returns"
        else -> "Not saved · Android may not rejoin after power-off"
    }

    /**
     * Watches every Wi-Fi network and reports the one whose SSID matches the ESP32.
     * A plain transport filter is used on purpose: a WifiNetworkSpecifier request
     * would pop the system "connect to..." dialog and is only tested separately.
     */
    fun registerEspNetwork(
        context: Context,
        onNetwork: (Network) -> Unit,
        onLost: () -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return null

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        // The ESP32 AP is usually already connected when the service starts (the
        // phone joined it earlier), and onLinkPropertiesChanged alone is not
        // always delivered for that pre-existing network - the service then sat
        // idle with no stream (observed 2026-10-30). Check the current state on
        // onAvailable too.
        fun accept(network: Network, lp: LinkProperties?) {
            val onEspSubnet = lp?.linkAddresses?.any {
                it.address.hostAddress?.startsWith(ESP_SUBNET_PREFIX) == true
            } == true
            if (!onEspSubnet) return
            if (espNetwork != network) {
                espNetwork = network
                EventLog.add("wifi", "ESP32 network up: $network on $ESP_SUBNET_PREFIX*")
                onNetwork(network)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                accept(network, cm.getLinkProperties(network))
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                // Primary match: the ESP32 AP always hands out 192.168.4.0/24.
                // SSID matching is kept as a fallback only, because Android 13 /
                // HyperOS returns "<unknown ssid>" for WifiInfo.getSSID() even
                // with ACCESS_FINE_LOCATION and NEARBY_WIFI_DEVICES granted
                // (observed on the POCO F3 GT, 2026-10-30).
                accept(network, lp)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // HyperOS delivers this callback for the already-connected AP but
                // not always onAvailable/onLinkPropertiesChanged, so re-check the
                // link here too.
                accept(network, cm.getLinkProperties(network))
                val info = caps.transportInfo as? WifiInfo
                if (info == null) {
                    EventLog.add("wifi", "caps for $network has no WifiInfo")
                    return
                }
                val ssid = info.ssid?.trim('"')
                val capsLine = "$network ssid='$ssid'"
                if (capsLine != lastCapsLog) {
                    lastCapsLog = capsLine
                    EventLog.add("wifi", "caps $capsLine")
                }
                if (ssid == null) return
                if (ssid != ESP_SSID) return
                if (espNetwork != network) {
                    espNetwork = network
                    EventLog.add("wifi", "ESP32 network up by SSID: $ssid on $network")
                    onNetwork(network)
                }
            }

            override fun onLost(network: Network) {
                if (espNetwork == network) {
                    espNetwork = null
                    EventLog.add("wifi", "ESP32 network lost")
                    onLost()
                }
            }
        }

        return try {
            cm.registerNetworkCallback(request, callback)
            EventLog.add("wifi", "network callback registered for SSID $ESP_SSID")
            // And check what is already connected: the phone is usually on the AP
            // before the service starts, and no callback is replayed for it.
            val existing = cm.allNetworks.mapNotNull { n -> cm.getLinkProperties(n)?.let { n to it } }
            EventLog.add("wifi", "existing networks: ${existing.size}")
            existing.forEach { (n, lp) ->
                val addrs = lp.linkAddresses.joinToString(",") { it.address.hostAddress ?: "?" }
                val iface = lp.interfaceName ?: "?"
                val transports = cm.getNetworkCapabilities(n)?.let { c ->
                    buildList {
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
                        if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
                    }.joinToString("+").ifEmpty { "?" }
                } ?: "?"
                EventLog.add("wifi", "  $n iface=$iface transports=$transports addrs=[$addrs]")
                accept(n, lp)
            }
            callback
        } catch (t: Throwable) {
            EventLog.add("wifi", "registerNetworkCallback failed: $t")
            null
        }
    }

    /**
     * Pins the phone to the ESP32 AP with a local-only WifiNetworkSpecifier
     * request.
     *
     * Why: HyperOS prefers a saved internet Wi-Fi. Measured 2026-10-30 on the
     * POCO F3 GT - after a Wi-Fi off/on and again after an ESP32 power cycle the
     * phone abandoned A0RETSSID for the home network and took the *default*
     * network with it, so telemetry never came back even though the AP was up
     * and the suggestion was registered. A specifier request is the only way to
     * hold the AP; because INTERNET is removed from the request the connection
     * stays local-only and cellular remains the default network (mode A).
     *
     * The system shows a one-time "allow this app to connect" dialog.
     */
    fun requestEspNetwork(
        context: Context,
        onNetwork: (Network) -> Unit,
        onLost: () -> Unit
    ): ConnectivityManager.NetworkCallback? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return null

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ESP_SSID)
            .setWpa2Passphrase(ESP_PASSPHRASE)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                espNetwork = network
                EventLog.add("wifi", "ESP32 network pinned: $network")
                onNetwork(network)
            }

            override fun onLost(network: Network) {
                if (espNetwork == network) {
                    espNetwork = null
                    EventLog.add("wifi", "ESP32 pinned network lost")
                    onLost()
                }
            }

            override fun onUnavailable() {
                EventLog.add("wifi", "pin request unavailable (AP absent or user denied)")
                onLost()
            }
        }

        return try {
            cm.requestNetwork(request, callback)
            EventLog.add("wifi", "pin request for SSID $ESP_SSID sent")
            callback
        } catch (t: Throwable) {
            EventLog.add("wifi", "requestNetwork failed: $t")
            null
        }
    }

    fun unregister(context: Context, callback: ConnectivityManager.NetworkCallback?) {
        if (callback == null) return
        try {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        } catch (t: Throwable) {
            EventLog.add("wifi", "unregisterNetworkCallback: $t")
        }
    }

    /**
     * Investigated alternative: the PendingIntent form. It is registered against
     * a WifiNetworkSpecifier, which may raise the system dialog; the result is
     * logged rather than assumed, since this form is optional for the design.
     */
    fun registerPendingIntentForm(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ESP_SSID)
            .setWpa2Passphrase(ESP_PASSPHRASE)
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(context, SuggestionReceiver::class.java)
            .setAction(SuggestionReceiver.ACTION_PENDING_CALLBACK)
        val pending = PendingIntent.getBroadcast(context, 41, intent, flags)

        return try {
            cm.registerNetworkCallback(request, pending)
            EventLog.add("wifi", "PendingIntent network callback registered (specifier form)")
            true
        } catch (t: Throwable) {
            EventLog.add("wifi", "PendingIntent callback failed: $t")
            false
        }
    }

    /** Human-readable transport of the process default network. */
    fun describeDefault(context: Context): Pair<String, Boolean?> {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return "-" to null
        val active = cm.activeNetwork ?: return "none" to null
        val caps = cm.getNetworkCapabilities(active) ?: return "none" to null
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return transport to validated
    }
}
