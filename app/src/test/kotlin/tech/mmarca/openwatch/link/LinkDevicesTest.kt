package tech.mmarca.openwatch.link

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkSyncResult
import org.junit.Assert.assertNull
import tech.mmarca.openwatch.features.watches.SyncAttempt
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncOutcome
import tech.mmarca.openwatch.devices.wearos.WearOsAppStatus
import tech.mmarca.openwatch.devices.wearos.WearOsLinkSnapshot
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleConfig
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class LinkDevicesTest {

    private val now = Instant.parse("2026-11-08T08:00:00Z")

    private fun device(
        id: String,
        kind: BleDeviceKind,
        integration: DeviceIntegration? = null,
        capabilities: Set<BleSensorCapability> = emptySet(),
    ) = BleSensorDevice(
        id = id,
        displayName = id,
        address = "AA:BB:CC:DD:EE:0${id.length}",
        bluetoothName = id,
        capabilities = capabilities,
        enabled = true,
        wheelCircumferenceMm = null,
        batteryPercent = 80,
        batteryUpdatedAt = now,
        addedAt = now,
        kind = kind,
        integration = integration,
        lastSyncedAt = now,
    )

    @Test
    fun `watches and the scale are listed, a plain sensor is not`() {
        val devices = linkDevices(
            registered = listOf(
                device("venu", BleDeviceKind.WATCH, DeviceIntegration.GARMIN),
                device("pixel", BleDeviceKind.WATCH, DeviceIntegration.WEAROS),
                device("strap", BleDeviceKind.SENSOR, capabilities = setOf(BleSensorCapability.HEART_RATE)),
            ),
            syncingDeviceId = "venu",
            wearOs = mapOf("pixel" to WearOsLinkSnapshot(WearOsAppStatus.APP_RUNNING, checkedAt = now)),
            scale = XiaomiScaleConfig(hasKey = true, address = "11:22:33:44:55:66", name = "Bathroom"),
            scaleStatus = ScaleListenerStatus.LISTENING,
        )

        assertEquals(listOf("venu", "pixel", SCALE_ID_PREFIX + "11:22:33:44:55:66"), devices.map { it.id })
        val garmin = devices[0]
        assertEquals(OpenLinkIntegration.GARMIN, garmin.integration)
        assertTrue(garmin.syncing)
        assertEquals(80, garmin.batteryPercent)
        assertEquals(now.toEpochMilli(), garmin.lastSyncedAtMillis)
        assertEquals(setOf(OpenLinkSampleType.HEART_RATE, OpenLinkSampleType.STEPS), garmin.liveSampleTypes)
        assertEquals(OpenLinkConnection.CONNECTED, devices[1].connection)
        assertEquals(setOf(OpenLinkSampleType.HEART_RATE), devices[1].liveSampleTypes)
        val scale = devices[2]
        assertEquals(OpenLinkDeviceKind.SCALE, scale.kind)
        assertEquals("Bathroom", scale.displayName)
        assertEquals(false, scale.supportsSync)
    }

    @Test
    fun `a scale without its key is not listed yet`() {
        val devices = linkDevices(emptyList(), null, emptyMap(), XiaomiScaleConfig(hasKey = false, address = "11:22"), ScaleListenerStatus.OFF)

        assertTrue(devices.isEmpty())
    }

    @Test
    fun `a legacy watch entry without an integration counts as Garmin`() {
        val devices = linkDevices(listOf(device("fenix", BleDeviceKind.WATCH)), null, emptyMap(), XiaomiScaleConfig(), ScaleListenerStatus.OFF)

        assertEquals(OpenLinkIntegration.GARMIN, devices.single().integration)
        assertEquals(OpenLinkConnection.UNKNOWN, devices.single().connection)
    }

    @Test
    fun `a watch carries how its last sync ended and when, one never tried carries neither`() {
        val devices = linkDevices(
            registered = listOf(device("venu", BleDeviceKind.WATCH, DeviceIntegration.GARMIN), device("pixel", BleDeviceKind.WATCH, DeviceIntegration.WEAROS)),
            syncingDeviceId = null,
            wearOs = emptyMap(),
            scale = XiaomiScaleConfig(),
            scaleStatus = ScaleListenerStatus.OFF,
            attempts = mapOf("venu" to SyncAttempt(DeviceSyncOutcome.PERMISSION_MISSING, now)),
        )

        val venu = devices.single { it.id == "venu" }
        assertEquals(OpenLinkSyncResult.PERMISSION_MISSING, venu.lastSyncResult)
        assertEquals(now.toEpochMilli(), venu.lastSyncAttemptAtMillis)
        val pixel = devices.single { it.id == "pixel" }
        assertNull(pixel.lastSyncResult)
        assertNull(pixel.lastSyncAttemptAtMillis)
    }
}
