package com.pocketmechanic.shift

/** One settings mutation the guided wizard has earned. */
sealed class SettingWrite {
    data class ThrottleIdle(val raw: Int) : SettingWrite()
    data class ThrottleFull(val raw: Int) : SettingWrite()
    data class GearRatio(val gear: Int, val ratio: Double) : SettingWrite()
    data class GpsScale(val scale: Double) : SettingWrite()
}

/**
 * What to write, and what was refused, when the wizard reports new results.
 *
 * Pure so it can be tested: "saved each step the moment it turns ✓" is the whole
 * point of the guided rewrite, and a save that silently did nothing is the exact
 * bug class that made the old GPS wizard useless (it stored a value nothing read).
 */
data class CalibrationApply(
    val writes: List<SettingWrite> = emptyList(),
    val refusals: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = writes.isEmpty() && refusals.isEmpty()
}

object CalibrationWrites {

    /** Smallest idle→full raw spread that can be a real pedal range. */
    const val MIN_THROTTLE_RANGE = 60

    /**
     * Diffs two wizard results. Idempotent: calling it again with the same
     * `after` produces nothing, so the caller may run it on every frame.
     *
     * [currentIdle] is used when the idle step was skipped but a full-throttle
     * capture still has to be range-checked against the stored idle value.
     */
    fun diff(
        before: GuidedResults,
        after: GuidedResults,
        currentIdle: Int,
        minRange: Int = MIN_THROTTLE_RANGE,
    ): CalibrationApply {
        val writes = mutableListOf<SettingWrite>()
        val refusals = mutableListOf<String>()

        after.idleThrottle?.let { value ->
            if (value != before.idleThrottle) writes += SettingWrite.ThrottleIdle(value)
        }
        after.fullThrottle?.let { value ->
            if (value != before.fullThrottle) {
                val idle = after.idleThrottle ?: currentIdle
                if (value - idle >= minRange) {
                    writes += SettingWrite.ThrottleFull(value)
                } else {
                    refusals += "full throttle $value is only ${value - idle} above idle " +
                        "$idle - need $minRange, not saved"
                }
            }
        }
        after.gearRatios.forEach { (gear, ratio) ->
            if (before.gearRatios[gear] != ratio) writes += SettingWrite.GearRatio(gear, ratio)
        }
        after.gpsScale?.let { value ->
            if (value != before.gpsScale) writes += SettingWrite.GpsScale(value)
        }
        return CalibrationApply(writes, refusals)
    }
}
