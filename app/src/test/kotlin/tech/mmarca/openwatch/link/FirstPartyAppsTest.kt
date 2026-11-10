package tech.mmarca.openwatch.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartyAppsTest {

    private val apps = FirstPartyApps(
        packageNames = setOf("tech.mmarca.openvitals", "tech.mmarca.openvitals.debug"),
        knownSigners = setOf("AA:BB:CC"),
    )

    @Test
    fun `every first-party name is known under the first-party keys, and no other name`() {
        assertEquals(
            mapOf("tech.mmarca.openvitals" to setOf("AA:BB:CC"), "tech.mmarca.openvitals.debug" to setOf("AA:BB:CC")),
            apps.knownClients,
        )
    }

    @Test
    fun `a first-party name under a known key is not flagged`() {
        assertFalse(apps.usesNameWithoutKey("tech.mmarca.openvitals", knownSigner = true))
    }

    @Test
    fun `a first-party name under another key is flagged`() {
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals", knownSigner = false))
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals.debug", knownSigner = false))
    }

    @Test
    fun `any other app is not flagged, whatever its key`() {
        assertFalse(apps.usesNameWithoutKey("com.example.other", knownSigner = false))
    }
}
