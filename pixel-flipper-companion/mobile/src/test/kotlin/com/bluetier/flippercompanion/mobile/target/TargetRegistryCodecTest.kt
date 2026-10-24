package com.bluetier.flippercompanion.mobile.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRegistryCodecTest {

    private val registry = TargetRegistry(
        listOf(
            Target(
                id = "tv",
                label = "Living room TV",
                bearing = Bearing(137.4, -2.1),
                action = "media_player.living_room_tv",
                bleAddress = "AA:BB:CC:DD:EE:FF",
                nfcTagUid = "04A2B3C4",
            ),
            Target(
                id = "fan",
                label = "Ceiling fan",
                bearing = Bearing(92.0, 38.5),
                action = "fan.ceiling",
            ),
        ),
    )

    @Test
    fun `round trips a registry`() {
        val decoded = TargetRegistryCodec.decode(TargetRegistryCodec.encode(registry))!!

        assertEquals(0, decoded.skippedLines)
        assertEquals(registry, decoded.registry)
    }

    @Test
    fun `round trips an empty registry`() {
        val decoded = TargetRegistryCodec.decode(TargetRegistryCodec.encode(TargetRegistry()))!!

        assertEquals(0, decoded.registry.size)
    }

    @Test
    fun `a label containing delimiters survives`() {
        // Without escaping, this label would be cut into pieces and the target
        // silently lost.
        val awkward = TargetRegistry(
            listOf(
                Target(
                    id = "odd",
                    label = "Kitchen; the =good= one \\ here",
                    bearing = Bearing(10.0, 0.0),
                    action = "light.kitchen",
                ),
            ),
        )

        val decoded = TargetRegistryCodec.decode(TargetRegistryCodec.encode(awkward))!!

        assertEquals("Kitchen; the =good= one \\ here", decoded.registry.byId("odd")!!.label)
        assertEquals(0, decoded.skippedLines)
    }

    @Test
    fun `optional fields round trip as null`() {
        val decoded = TargetRegistryCodec.decode(TargetRegistryCodec.encode(registry))!!
        val fan = decoded.registry.byId("fan")!!

        assertNull(fan.bleAddress)
        assertNull(fan.nfcTagUid)
    }

    @Test
    fun `an unknown version is refused`() {
        val text = TargetRegistryCodec.encode(registry).replaceFirst("v=1", "v=99")

        assertNull(TargetRegistryCodec.decode(text))
    }

    @Test
    fun `missing or junk input is refused rather than throwing`() {
        assertNull(TargetRegistryCodec.decode(""))
        assertNull(TargetRegistryCodec.decode("garbage"))
    }

    @Test
    fun `one corrupt line costs one target, not the file`() {
        val text = TargetRegistryCodec.encode(registry)
            .lines()
            .toMutableList()
            .apply { add(2, "id=broken;label=no bearing here") }
            .joinToString("\n")

        val decoded = TargetRegistryCodec.decode(text)!!

        assertEquals(1, decoded.skippedLines)
        assertEquals(2, decoded.registry.size)
        assertTrue(decoded.registry.byId("tv") != null && decoded.registry.byId("fan") != null)
    }

    @Test
    fun `a repeated id keeps the last definition`() {
        val text = buildString {
            append("v=1\n")
            append("id=tv;label=Old;yaw=10.0;pitch=0.0;action=a\n")
            append("id=tv;label=New;yaw=20.0;pitch=0.0;action=b\n")
        }

        val decoded = TargetRegistryCodec.decode(text)!!

        assertEquals(1, decoded.registry.size)
        assertEquals("New", decoded.registry.byId("tv")!!.label)
    }

    @Test
    fun `decimal values are locale-independent`() {
        // Double.toString and String.toDouble always use a dot; String.format
        // would not, and a comma would collide with nothing here but would
        // break parsing on a European locale.
        val text = TargetRegistryCodec.encode(registry)

        assertTrue(text.contains("yaw=137.4"))
        assertEquals(137.4, TargetRegistryCodec.decode(text)!!.registry.byId("tv")!!.bearing.yawDegrees, 1e-9)
    }
}
