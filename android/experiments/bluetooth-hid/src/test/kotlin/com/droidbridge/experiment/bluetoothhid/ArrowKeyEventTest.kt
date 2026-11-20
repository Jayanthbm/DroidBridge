package com.droidbridge.experiment.bluetoothhid

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowKeyEventTest {
    @Test
    fun arrowUsagesMatchTheStandardKeyboardPage() {
        assertEquals(0x52, HidKeyUsage.UP)
        assertEquals(0x51, HidKeyUsage.DOWN)
        assertEquals(0x50, HidKeyUsage.LEFT)
        assertEquals(0x4F, HidKeyUsage.RIGHT)
        assertEquals(4, setOf(HidKeyUsage.UP, HidKeyUsage.DOWN, HidKeyUsage.LEFT, HidKeyUsage.RIGHT).size)
    }
}
