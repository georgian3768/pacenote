package com.pocketmechanic.shift

/**
 * Keeps the last confidently estimated gear on screen for a moment while the
 * estimator itself is silent.
 *
 * The estimator needs rpm and speed in the same frame and suppresses everything
 * below 15 km/h, so it drops out during a clutch-in and below walking pace. On
 * the 72 dp bubble a gear that blinks off at every shift is worse than no gear
 * at all, so the last value is held for [holdMs].
 *
 * The hold never overrides a fresh estimate and never survives a [reset], which
 * the overlay calls when the link or the cue changes: showing "3" while the
 * driver has already shifted to 4 would be actively misleading, and holding a
 * gear across a cue is exactly that moment.
 */
class GearDisplay(private val holdMs: Long = DEFAULT_HOLD_MS) {

    private var held: Int? = null
    private var heldAtMs: Long = 0L

    /**
     * @param gear the estimate for this frame, null when there is none.
     * @param nowMs monotonic time (elapsedRealtime).
     * @return the gear to display, or null.
     */
    fun update(gear: Int?, nowMs: Long): Int? {
        if (gear != null) {
            held = gear
            heldAtMs = nowMs
            return gear
        }
        val last = held ?: return null
        if (nowMs - heldAtMs > holdMs) {
            held = null
            return null
        }
        return last
    }

    /** Drop the hold immediately (stream lost, cue fired, trip ended). */
    fun reset() {
        held = null
    }

    companion object {
        /**
         * Long enough to bridge a shift (cluster matching recovers within a few
         * hundred ms once rpm and speed are back) and short enough that a gear
         * cannot linger into the next one.
         */
        const val DEFAULT_HOLD_MS = 1_500L
    }
}
