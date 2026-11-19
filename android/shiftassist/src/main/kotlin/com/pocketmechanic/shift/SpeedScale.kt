package com.pocketmechanic.shift

/**
 * Converts the raw CAN speed into the speed the assistant reasons about.
 *
 * The GPS wizard measures `GPS / CAN` and stores it as `gpsSpeedScale`; until
 * 2026-11-05 that number was saved and never read, so calibrating it changed
 * nothing and the gear estimate kept failing against default ratios that were
 * derived from a differently scaled speed.
 *
 * Kept here (not in the app module) so the arithmetic is unit tested: the bug was
 * a missing multiplication, which no amount of UI tapping would have caught.
 */
object SpeedScale {

    /** Scale accepted by the wizard; anything outside is treated as unset. */
    const val MIN_PLAUSIBLE = 0.5
    const val MAX_PLAUSIBLE = 2.0

    /**
     * Applies [scale] to a raw km/h reading.
     *
     * A null reading stays null (no speed signal must not become 0 km/h, which
     * would look like a stopped car and clear the gear estimate). An implausible
     * or non-positive scale is ignored rather than trusted, so a corrupted
     * preference cannot silently double every speed.
     */
    fun apply(rawKph: Float?, scale: Double): Double? {
        if (rawKph == null) return null
        val usable = scale.takeIf { it in MIN_PLAUSIBLE..MAX_PLAUSIBLE } ?: 1.0
        return rawKph * usable
    }

    /** Same, for the Float-typed call sites (overlay text, calibration wizard). */
    fun applyFloat(rawKph: Float?, scale: Double): Float? =
        apply(rawKph, scale)?.toFloat()
}
