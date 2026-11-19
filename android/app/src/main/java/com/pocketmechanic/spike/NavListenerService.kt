package com.pocketmechanic.spike

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide "a navigation session is running" flag.
 *
 * Written by [NavListenerService] (main thread) and read by [SpikeService] when
 * it starts and from the listener below. The notification listener can be bound
 * before, after or without the telemetry service, so this has to outlive both:
 * when it changes, whoever is alive hears it, and a late starter asks for the
 * current value instead of waiting for the next notification.
 */
object NavState {

    @Volatile
    var isNavigating = false
        private set

    /** True once the system has bound our listener (notification access granted). */
    @Volatile
    var isConnected = false
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun setConnected(connected: Boolean) {
        if (isConnected == connected) return
        isConnected = connected
        EventLog.add("nav", if (connected) "listener connected" else "listener disconnected")
    }

    fun setNavigating(navigating: Boolean) {
        if (isNavigating == navigating) return
        isNavigating = navigating
        EventLog.add(
            "nav",
            if (navigating) "navigation started -> HUD" else "navigation ended -> bubble",
        )
        for (listener in listeners) {
            runCatching { listener(navigating) }
        }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}

/**
 * Turns the navigation notification into the HUD switch.
 *
 * There is no public API for "a navigation session is running": the supported
 * signal is the turn-by-turn notification that Maps and Waze keep posted while
 * navigating, with `CATEGORY_NAVIGATION`. Both apps tag that notification (and
 * only that one) with the category, so the package filter plus the category is
 * enough to tell navigation from a search result or a Maps promotion.
 *
 * The set of active navigation keys is the state, not a single boolean: Maps
 * can post a second navigation notification (a re-route, a split-screen
 * variant), and removing one of them must not end the session while another is
 * still up. [onListenerConnected] rebuilds the set from `activeNotifications`
 * because a process restart loses everything the callbacks said earlier.
 *
 * The service is BOUND by the system once notification access is granted; no
 * code starts it, and there is nothing to retry. With access revoked it is
 * unbound and [NavState] falls back to the bubble.
 */
class NavListenerService : NotificationListenerService() {

    /** Keys of the navigation notifications currently posted. Main thread only. */
    private val navKeys = mutableSetOf<String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        EventLog.init(this)
        NavState.setConnected(true)
        syncActive()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NavState.setConnected(false)
        navKeys.clear()
        NavState.setNavigating(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null && isNavigation(sbn) && navKeys.add(sbn.key)) {
            NavState.setNavigating(true)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn != null && navKeys.remove(sbn.key)) {
            NavState.setNavigating(navKeys.isNotEmpty())
        }
    }

    /** Rebuild the key set from what is actually posted right now. */
    private fun syncActive() {
        navKeys.clear()
        runCatching { activeNotifications ?: emptyArray() }
            .getOrDefault(emptyArray())
            .filter { isNavigation(it) }
            .forEach { navKeys.add(it.key) }
        NavState.setNavigating(navKeys.isNotEmpty())
    }

    private fun isNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in NAV_PACKAGES) return false
        return sbn.notification?.category == android.app.Notification.CATEGORY_NAVIGATION
    }

    companion object {
        /** Packages whose navigation notification is trusted. */
        val NAV_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "com.waze",
        )
    }
}

/**
 * Notification access is granted to this app, not to a specific service; the
 * package list is the supported check (`enabled_notification_listeners` read by
 * hand misses the component and can include stale entries).
 */
fun navListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** The landing row's wording: access is the whole feature, so say it plainly. */
fun navListenerDescription(context: Context): String =
    if (navListenerEnabled(context)) {
        "On · Maps and Waze switch the bubble to the edge HUD"
    } else {
        "Needs notification access · tap to grant"
    }

/**
 * Opens notification access for this app.
 *
 * API 30+ has a per-listener detail page, which is the only one that shows a
 * switch for `NavListenerService` directly; older releases open the full list.
 * Returns false when the ROM resolves neither screen, so the caller can say so
 * instead of leaving a tap that does nothing.
 */
fun openNavListenerSettings(activity: Activity): Boolean {
    val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(activity, NavListenerService::class.java),
        )
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(detail, list)
    } else {
        listOf(list)
    }
    for (intent in candidates) {
        if (activity.packageManager.resolveActivity(intent, 0) == null) continue
        val started = runCatching { activity.startActivity(intent) }.isSuccess
        if (started) return true
    }
    return false
}
