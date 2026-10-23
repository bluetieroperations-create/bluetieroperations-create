package com.bluetier.flippercompanion.mobile.wear

import com.bluetier.flippercompanion.shared.WearContract
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone<->watch contract lives in code (WearContract) but is also spelled out
 * as strings in XML. If they drift, messages are silently never delivered, so
 * pin them together. Gradle runs unit tests from the module directory.
 */
class WearContractManifestTest {

    @Test fun listenerPathPrefixMatchesContractPaths() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val prefix = Regex("""android:pathPrefix="([^"]+)"""").find(manifest)?.groupValues?.get(1)
            ?: error("PhoneMessageListenerService has no pathPrefix")

        // Every path the watch sends *to the phone*, not just the trigger: a new
        // path that falls outside the prefix is never delivered, and nothing
        // fails loudly when that happens.
        listOf(WearContract.PATH_TRIGGER, WearContract.PATH_SNAP_TRACE).forEach { path ->
            assertTrue("$path is not covered by pathPrefix $prefix", path.startsWith(prefix))
        }
    }

    @Test fun advertisedCapabilityMatchesContract() {
        val wearXml = File("src/main/res/values/wear.xml").readText()
        assertTrue(wearXml.contains("<item>${WearContract.CAPABILITY_PHONE_APP}</item>"))
    }
}
