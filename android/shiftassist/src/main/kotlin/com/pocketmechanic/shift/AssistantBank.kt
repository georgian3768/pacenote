package com.pocketmechanic.shift

/**
 * The mode the driver selected. [ECO], [NORMAL] and [SPORT] are the three
 * [DriveMode] profiles; [SHADOW] runs all three on every frame and renders
 * nothing, so a drive can be scored against every profile at once without the
 * driver being told what to do.
 *
 * SHADOW exists for measurement, not for driving: it changes no cue decision,
 * it only decides which of the three answers is allowed to reach the screen.
 */
enum class AssistantMode(val profile: DriveMode?) {
    ECO(DriveMode.ECO),
    NORMAL(DriveMode.NORMAL),
    SPORT(DriveMode.SPORT),
    SHADOW(null);

    val shadow: Boolean get() = this == SHADOW

    /**
     * The next driving profile in the panel chip's cycle: ECO -> NORMAL ->
     * SPORT -> ECO. SHADOW is measurement, not a fourth driving mode, so
     * leaving it goes to the default instead of entering the cycle.
     */
    fun next(): AssistantMode = when (this) {
        ECO -> NORMAL
        NORMAL -> SPORT
        SPORT -> ECO
        SHADOW -> DEFAULT
    }

    companion object {
        /**
         * The factory default and the fresh-install fallback: ECO, the mileage
         * profile. It is also where an unrecognised stored value lands, so a
         * handset with no preference (or a corrupted one) starts economical
         * rather than in the middle profile.
         */
        val DEFAULT = ECO

        fun of(name: String?): AssistantMode = when (name?.lowercase()) {
            "eco" -> ECO
            "normal" -> NORMAL
            "sport" -> SPORT
            "shadow" -> SHADOW
            else -> DEFAULT
        }
    }
}

/**
 * Runs the shift assistant for the selected mode, or all three at once in
 * [AssistantMode.SHADOW].
 *
 * All instances share one [GearEstimator] (it is stateless, so this is mostly a
 * promise that every profile sees the same gear) but keep their own rate
 * limits, re-arm latches and gear-change blank: they are independent decisions
 * on the same frame, which is exactly what the shadow log compares.
 */
class AssistantBank(
    private val cfg: Config = Config(),
    private val estimator: GearEstimator,
    initial: AssistantMode = AssistantMode.NORMAL,
) {
    private val assistants: Map<DriveMode, ShiftAssistant> =
        DriveMode.entries.associateWith { mode ->
            ShiftAssistant(cfg, estimator).also { it.mode = mode }
        }

    var mode: AssistantMode = initial
        set(value) {
            field = value
        }

    /**
     * Feeds one frame. [FrameDecision.visible] is null in SHADOW: the caller
     * must not render a bubble, play a cue or move a directional indicator.
     */
    fun update(sample: Sample, warm: Boolean = true): FrameDecision {
        val running: List<DriveMode> =
            if (mode.shadow) DriveMode.entries else listOfNotNull(mode.profile)
        val outputs = running.associateWith { assistants.getValue(it).update(sample, warm) }
        return FrameDecision(
            mode = mode,
            visible = mode.profile?.let { outputs[it] },
            outputs = outputs,
        )
    }

    fun reset() {
        assistants.values.forEach { it.reset() }
    }

    /** The instance for [mode]; null for SHADOW, which has no single profile. */
    fun assistantFor(mode: DriveMode): ShiftAssistant? = assistants[mode]
}

data class FrameDecision(
    val mode: AssistantMode,
    /** Output for the selected profile; null in SHADOW. */
    val visible: Output?,
    /** Every profile that ran this frame, in enum order. */
    val outputs: Map<DriveMode, Output>,
)
