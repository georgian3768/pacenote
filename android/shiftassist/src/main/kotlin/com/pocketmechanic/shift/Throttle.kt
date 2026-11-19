package com.pocketmechanic.shift

/**
 * Throttle scaling, in one place so the cue logic and the card cannot disagree.
 *
 * The wire value (`thr1`) is a raw 8-bit channel, not a pedal percentage, and it
 * is not pedal-linear. Two points are measured by the guided wizard: the value
 * at closed throttle (idle raw) and at full throttle (full raw). Until both are
 * known the only honest value is `raw / 255`, which is what [normalized] falls
 * back to - that is not a percentage of pedal travel, just the raw byte scaled.
 *
 * The idle offset matters: on this car idle raw is 42, so `42 / 250` reports 17 %
 * throttle with the pedal released, and every demand-based shift threshold is
 * then biased by that offset.
 */
object Throttle {

    /**
     * @param raw wire value, null when the stream has no fresh throttle.
     * @param idleRaw closed-throttle value from the wizard; a negative value
     *   means "not measured" and disables the idle offset.
     * @param fullRaw full-throttle value from the wizard; must be above
     *   [idleRaw] for the offset to be usable.
     * @return 0..1, or null when there is no usable raw value.
     */
    fun normalized(raw: Int?, idleRaw: Int, fullRaw: Int): Double? {
        if (raw == null || fullRaw <= 0) return null
        val calibrated = idleRaw in 0 until fullRaw
        val fraction = if (calibrated) {
            (raw - idleRaw).toDouble() / (fullRaw - idleRaw)
        } else {
            raw.toDouble() / fullRaw
        }
        return fraction.coerceIn(0.0, 1.0)
    }
}
