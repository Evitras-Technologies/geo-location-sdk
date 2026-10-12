package com.openbackgroundgeolocation.util

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

/**
 * The SDK's one CPU wake lock, only ever held for a bounded time.
 *
 * Tracking used to hold a partial wake lock for as long as the foreground
 * service ran — up to 24 hours, parked or not. A device parked overnight then
 * never let its CPU sleep, which is the single largest battery and heat cost a
 * tracker can have. Now the lock is taken in short, self-expiring spans:
 *
 * - while moving, each GPS fix renews it (see [MOVING_MILLIS]), so handler
 *   timers such as the stop timeout keep real time. It lapses on its own if
 *   fixes stop arriving;
 * - around each event handed to JavaScript (see [EVENT_MILLIS]), so the app's
 *   listener can finish its work — typically an upload — before the CPU sleeps;
 * - while stationary it is released: the stationary alarm, significant-motion
 *   sensor and activity recognition all wake the device themselves.
 */
object WakeLocks {

    /** Renewed on every fix while moving; lapses this long after the last one. */
    const val MOVING_MILLIS = 10 * 60 * 1000L

    /** Covers one JS listener run, including a network round trip. */
    const val EVENT_MILLIS = 15 * 1000L

    private const val TAG = "OpenBackgroundGeolocation::TrackingWakeLock"

    private var lock: PowerManager.WakeLock? = null

    /** `elapsedRealtime` at which the current hold expires; 0 when not held. */
    private var heldUntil = 0L

    /**
     * Keep the CPU awake for at least [millis] from now.
     *
     * Never shortens an existing hold: a 15-second event hold arriving while a
     * 10-minute moving hold is active leaves the longer one in place.
     */
    @Synchronized
    fun holdFor(context: Context, millis: Long) {
        runCatching {
            val until = SystemClock.elapsedRealtime() + millis
            val wakeLock = lock ?: newLock(context).also { lock = it }
            if (wakeLock.isHeld && until <= heldUntil) return
            // Non-reference-counted: re-acquiring replaces the pending timeout.
            wakeLock.acquire(millis)
            heldUntil = until
        }
    }

    /** Drop any hold now, e.g. on entering the stationary state or stopping. */
    @Synchronized
    fun release() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        heldUntil = 0L
    }

    private fun newLock(context: Context): PowerManager.WakeLock {
        val powerManager =
            context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
        }
    }
}
