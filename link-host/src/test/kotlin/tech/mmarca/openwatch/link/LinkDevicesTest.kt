package tech.mmarca.openwatch.link

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.OpenLinkSyncResult
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.FakeDeviceFamily
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncResult
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.devices.core.sync.SkipReason
import tech.mmarca.openwatch.devices.core.sync.SkippedSeries
import tech.mmarca.openwatch.devices.core.sync.SyncAttempt
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorCapability
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

class LinkDevicesTest {

    private val now = Instant.parse("2026-11-08T08:00:00Z")

    private val garmin = FakeDeviceFamily(
        "GARMIN",
        setOf(DeviceFeature.SYNC, DeviceFeature.FIND, DeviceFeature.LIVE_HEART_RATE, DeviceFeature.LIVE_STEPS),
    ) { it.kind != BleDeviceKind.SENSOR && (it.integration == DeviceIntegration.GARMIN || it.integration == null) }
    private val wearOs = FakeDeviceFamily("WEAROS", setOf(DeviceFeature.SYNC, DeviceFeature.LIVE_HEART_RATE)) {
        it.kind == BleDeviceKind.WATCH && it.integration == DeviceIntegration.WEAROS
    }
    private val xiaomi = FakeDeviceFamily("XIAOMI", emptySet()) { it.kind == BleDeviceKind.SCALE && it.integration == DeviceIntegration.XIAOMI }
    private val families = DeviceFamilies(mapOf("GARMIN" to garmin, "WEAROS" to wearOs, "XIAOMI" to xiaomi))

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
                device("bathroom", BleDeviceKind.SCALE, DeviceIntegration.XIAOMI),
            ),
            syncingDeviceIds = setOf("venu"),
            families = families,
            connections = mapOf("pixel" to true, "bathroom" to true),
        )

        // The scale goes by its registry id too: its address stays on the phone.
        assertEquals(listOf("venu", "pixel", "bathroom"), devices.map { it.id })
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
        assertEquals(OpenLinkIntegration.XIAOMI, scale.integration)
        assertEquals(OpenLinkConnection.CONNECTED, scale.connection)
        assertEquals(false, scale.supportsSync)
        assertEquals(false, scale.supportsRecording)
    }

    @Test
    fun `a legacy watch entry without an integration counts as Garmin`() {
        val devices = linkDevices(listOf(device("fenix", BleDeviceKind.WATCH)), emptySet(), families, emptyMap())

        assertEquals(OpenLinkIntegration.GARMIN, devices.single().integration)
        assertEquals(OpenLinkConnection.UNKNOWN, devices.single().connection)
    }

    @Test
    fun `a family newer than the contract's constants goes by its own name`() {
        val acme = FakeDeviceFamily("ACME", setOf(DeviceFeature.SYNC)) { it.kind == BleDeviceKind.WATCH }

        val devices = linkDevices(
            listOf(device("band", BleDeviceKind.WATCH)),
            emptySet(),
            DeviceFamilies(mapOf("ACME" to acme)),
            emptyMap(),
        )

        assertEquals("ACME", devices.single().integration.name)
        assertEquals(OpenLinkDeviceKind.WATCH, devices.single().kind)
    }

    @Test
    fun `only a device that streams can be recorded with, whatever its kind`() {
        val acme = FakeDeviceFamily("ACME", emptySet()) { it.integration == DeviceIntegration("ACME") && it.kind != BleDeviceKind.SENSOR }
        val strapFamily = FakeDeviceFamily("STRAPS", setOf(DeviceFeature.LIVE_HEART_RATE)) { it.integration == DeviceIntegration("STRAPS") }

        val devices = linkDevices(
            listOf(
                device("cuff", BleDeviceKind("BLOOD_PRESSURE_MONITOR"), DeviceIntegration("ACME")),
                device("strap", BleDeviceKind.SENSOR, DeviceIntegration("STRAPS")),
            ),
            emptySet(),
            DeviceFamilies(mapOf("ACME" to acme, "STRAPS" to strapFamily)),
            emptyMap(),
        )

        assertEquals("BLOOD_PRESSURE_MONITOR", devices[0].kind.name)
        assertEquals(false, devices[0].supportsRecording)
        assertEquals(true, devices[1].supportsRecording)
        assertEquals(setOf(OpenLinkSampleType.HEART_RATE), devices[1].liveSampleTypes)
    }

    @Test
    fun `a device carries how its last sync ended, and when`() {
        val watch = device("Galaxy Watch8", BleDeviceKind.WATCH, DeviceIntegration.WEAROS)
        val attempt = SyncAttempt(DeviceSyncResult.Failed(SyncFailure.Interrupted), atMillis = 1_234L)
        val shown = linkDevices(listOf(watch), emptySet(), families, emptyMap(), mapOf(watch.id to attempt)).single()
        assertEquals(OpenLinkSyncResult.INTERRUPTED, shown.lastSyncResult)
        assertEquals(1_234L, shown.lastSyncAttemptAtMillis)
        assertNull(linkDevices(listOf(watch), emptySet(), families, emptyMap()).single().lastSyncResult)
    }

    @Test
    fun `a missing grant, also for a series left out, is permission missing, and out of reach is disconnected`() {
        val leftOut = DeviceSyncResult.Succeeded(skipped = setOf(SkippedSeries(HealthSeries.STEPS, SkipReason.PERMISSION)))
        assertEquals(OpenLinkSyncResult.PERMISSION_MISSING, linkSyncResult(leftOut))
        assertEquals(OpenLinkSyncResult.SUCCEEDED, linkSyncResult(DeviceSyncResult.Succeeded(skipped = setOf(SkippedSeries(HealthSeries.SKIN_TEMPERATURE, SkipReason.NO_BASELINE)))))
        assertEquals(OpenLinkSyncResult.PERMISSION_MISSING, linkSyncResult(DeviceSyncResult.Failed(SyncFailure.HealthConnectPermission)))
        assertEquals(OpenLinkSyncResult.DISCONNECTED, linkSyncResult(DeviceSyncResult.Failed(SyncFailure.Unreachable)))
        assertEquals(OpenLinkSyncResult.FAILED, linkSyncResult(DeviceSyncResult.Failed(SyncFailure.RadioBusy)))
    }
}
