package com.bluetier.flippercompanion.mobile.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdPidsTest {

    @Test fun decodesRpm() {
        // (0x1A*256 + 0xF8) / 4 = 1726
        assertEquals("RPM 1726", ObdPids.decode("41 0C 1A F8"))
    }

    @Test fun decodesSpeedAndTemps() {
        assertEquals("Speed 80 km/h", ObdPids.decode("41 0D 50"))
        assertEquals("Coolant 50 °C", ObdPids.decode("41 05 5A")) // 0x5A - 40
        assertEquals("Intake air -4 °C", ObdPids.decode("41 0F 24")) // 0x24 - 40
    }

    @Test fun decodesControlModuleVoltage() {
        // (0x37*256 + 0x70) / 1000 = 14.192 V
        assertEquals("Module voltage 14.192 V", ObdPids.decode("41 42 37 70"))
    }

    @Test fun toleratesMissingSpaces() {
        assertEquals("Speed 80 km/h", ObdPids.decode("410D50"))
    }

    @Test fun rejectsNonMode01OrUnknownPid() {
        assertNull(ObdPids.decode("NO DATA"))
        assertNull(ObdPids.decode("7F 01 12")) // negative response
        assertNull(ObdPids.decode("41 99 00")) // unknown PID
        assertNull(ObdPids.decode("41")) // too short
        assertNull(ObdPids.decode("41 0C 1A")) // RPM needs two data bytes
    }
}
