package com.pocketmechanic.spike

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Counts our started activities so the service can tell whether the app is on
 * screen without asking the system.
 *
 * `started`, not `resumed`: the overlay-permission sheet, a system dialog or a
 * second activity all keep the app in front, and the bubble must stay
 * suppressed through all of them.
 */
class PocketMechanicApp : Application() {

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = AppForeground.enter()
            override fun onActivityStopped(activity: Activity) = AppForeground.leave()

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

/**
 * Process-wide "is our own UI on screen" signal.
 *
 * The counter is the fast path: the overlay must come down the moment the last
 * activity stops. It is backed by the process's own importance because a
 * lifecycle callback can be missed (a service restarted into an existing
 * process sees no `onActivityStarted`), and a counter that is one high would
 * keep the bubble hidden for the whole drive.
 *
 * Listeners are notified on the main thread from the lifecycle callbacks; the
 * service uses one to re-run its overlay policy immediately instead of waiting
 * for the next one-second tick. That is the difference between "the bubble
 * appears when I press Home" and "the bubble appears when I open another app".
 */
object AppForeground {

    private val started = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** The lifecycle counter alone; kept for the log line. */
    val isForeground: Boolean get() = started.get() > 0

    /**
     * True while one of our activities is visible, however we found out.
     *
     * `IMPORTANCE_FOREGROUND` is the state of a process with a visible
     * activity. Our foreground service makes the *background* importance 125
     * (`IMPORTANCE_FOREGROUND_SERVICE`), so that value must not count as
     * foreground here - the service is always running, and counting it would
     * suppress the bubble forever.
     */
    fun isUiVisible(): Boolean {
        if (started.get() > 0) return true
        return runCatching {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }.getOrDefault(false)
    }

    fun enter() {
        started.incrementAndGet()
        notifyListeners()
    }

    fun leave() {
        // Clamp: a process that is missing an onActivityStarted (service
        // relaunch, lifecycle callback registered late) must not go negative and
        // then need two screens to become foreground again.
        started.updateAndGet { if (it > 0) it - 1 else 0 }
        notifyListeners()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }
}
