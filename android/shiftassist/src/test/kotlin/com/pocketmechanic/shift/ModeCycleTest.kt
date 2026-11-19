package com.pocketmechanic.shift

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The chip's cycle, tested as a contract rather than as three thresholds.
 *
 * The old chip was a two-state toggle over a three-profile model, so the
 * factory default had no route back once the driver left it. These tests are
 * the guard against that returning: the default must be in the cycle, every
 * driving profile must be reachable, and SHADOW must never be.
 */
class ModeCycleTest {

    @Test
    @DisplayName("the factory default is eco")
    fun defaultIsEco() {
        assertEquals(AssistantMode.ECO, AssistantMode.DEFAULT)
        assertEquals(DriveMode.ECO, DriveMode.DEFAULT)
        assertEquals(AssistantMode.ECO, AssistantMode.of(null))
        assertEquals(AssistantMode.ECO, AssistantMode.of("garbage"))
    }

    @Test
    @DisplayName("the chip cycles eco -> normal -> zoom -> eco")
    fun cycleOrder() {
        assertEquals(AssistantMode.NORMAL, AssistantMode.ECO.next())
        assertEquals(AssistantMode.SPORT, AssistantMode.NORMAL.next())
        assertEquals(AssistantMode.ECO, AssistantMode.SPORT.next())
    }

    @Test
    @DisplayName("the fresh-install default is reachable from every driving mode")
    fun defaultIsReachable() {
        val drive = AssistantMode.values().filter { !it.shadow }
        for (mode in drive) {
            val seen = generateSequence(mode) { it.next() }.take(4).toList()
            assertTrue(
                AssistantMode.DEFAULT in seen,
                "$mode cannot reach the default mode",
            )
        }
    }

    @Test
    @DisplayName("three taps return to the starting profile")
    fun cycleIsClosed() {
        for (mode in listOf(AssistantMode.ECO, AssistantMode.NORMAL, AssistantMode.SPORT)) {
            assertEquals(mode, mode.next().next().next())
        }
    }

    @Test
    @DisplayName("SHADOW is not reachable from the chip")
    fun shadowIsNotSelectable() {
        for (mode in AssistantMode.values()) {
            assertNotEquals(AssistantMode.SHADOW, mode.next())
        }
    }

    @Test
    @DisplayName("leaving SHADOW returns to a driving profile, not the measurement mode")
    fun shadowExit() {
        assertFalse(AssistantMode.SHADOW.next().shadow)
        assertEquals(AssistantMode.DEFAULT, AssistantMode.SHADOW.next())
    }

    @Test
    @DisplayName("only SHADOW runs without a profile")
    fun profilesMatchModes() {
        assertEquals(DriveMode.ECO, AssistantMode.ECO.profile)
        assertEquals(DriveMode.NORMAL, AssistantMode.NORMAL.profile)
        assertEquals(DriveMode.SPORT, AssistantMode.SPORT.profile)
        assertEquals(null, AssistantMode.SHADOW.profile)
    }
}
