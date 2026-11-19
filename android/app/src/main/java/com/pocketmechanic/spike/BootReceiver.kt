package com.pocketmechanic.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Startup case (f): does a BOOT_COMPLETED start survive Android 13's foreground
 * service restrictions? The result is logged, not assumed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        EventLog.add("boot", "BOOT_COMPLETED received, attempting service start")
        SpikeService.start(context)
    }
}
