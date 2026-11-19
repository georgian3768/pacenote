package com.pocketmechanic.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Network

/**
 * Receives the post-connection broadcast the system sends after auto-joining the
 * suggested network, and the optional PendingIntent form of the network callback.
 * Every outcome is timestamped so the protocol can quote it.
 */
class SuggestionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PENDING_CALLBACK = "com.pocketmechanic.spike.PENDING_CALLBACK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.wifi.action.WIFI_NETWORK_SUGGESTION_POST_CONNECTION" -> {
                val network: Network? =
                    intent.getParcelableExtra("android.net.wifi.extra.NETWORK")
                EventLog.add(
                    "suggestion",
                    "POST_CONNECTION broadcast, network=${network ?: "unknown"}"
                )
                // The broadcast is the earliest signal that the phone joined the
                // AP, so it is a valid place to attempt a start.
                SpikeService.start(context)
            }

            ACTION_PENDING_CALLBACK -> {
                val available = intent.getBooleanExtra(
                    "android.net.extra.NETWORK_CAPABILITY_VALIDATED", false
                )
                EventLog.add("wifi", "PendingIntent callback received (validated=$available)")
            }

            else -> EventLog.add("suggestion", "unhandled action ${intent.action}")
        }
    }
}
