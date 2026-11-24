package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/** The spot measurements over sources that hand their readings at once, and a real store. */
@RunWith(AndroidJUnit4::class)
class SpotMeasurementsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = MetricStore(app, name = null)
    private val entries = EntryLog(store)
    private var granted = true

    @After
    fun close() = store.close()

    /** A source that hands [readings] as soon as it is listened to, and counts its open handles. */
    private class FakeSensor<T>(private val readings: List<T>, private val refuses: Boolean = false) : SpotSensor<T> {
        var listened = 0
        var open = 0

        override fun listen(onReading: (T) -> Unit): AutoCloseable? {
            listened++
            if (refuses) return null
            open++
            readings.forEach(onReading)
            return AutoCloseable { open-- }
        }
    }

    private val claim = SensorClaim()
    private val spo2Claim = SensorClaim()

    /** The platform's SpO2 tracker: hands [batches] once started, on its handler, and counts its starts. */
    private class FakeSpo2(private val batches: List<List<Spo2Reading>>) : SpotSource<Spo2Reading> {
        var started = 0

        override val isAvailable: Boolean = true

        override fun start(handler: android.os.Handler, sink: SpotSource.Sink<Spo2Reading>): Boolean {
            started++
            batches.forEach(sink::onReadings)
            return true
        }

        override fun stop() = Unit
    }

    /** A tracker that hands the next of [runs] at each start, on its handler, and counts its starts. */
    private class FakeTracker<T>(private val runs: List<List<List<T>>>) : SpotSource<T> {
        var started = 0

        override val isAvailable: Boolean = true

        override fun start(handler: android.os.Handler, sink: SpotSource.Sink<T>): Boolean {
            runs.getOrNull(started++)?.forEach(sink::onReadings)
            return true
        }

        override fun stop() = Unit
    }

    private val wearer = tech.mmarca.healthcalculations.preferences.BodyProfile(birthYear = 1990, weightKg = 80.0, heightCm = 185.0)

    private fun spot(
        heartRate: SpotSensor<LiveHeartRate.Reading>? = null,
        heartBeats: SpotSensor<Double>? = null,
        motion: SpotSensor<Motion>? = null,
        pressure: SpotSensor<Float>? = null,
        spo2: SpotSource<Spo2Reading>? = null,
        ecg: SpotSource<EcgSample>? = null,
        body: SpotSource<BodyCompositionReading>? = null,
        profile: tech.mmarca.healthcalculations.preferences.BodyProfile? = wearer,
        limit: Long = 300L,
    ) = SpotMeasurements(
        app,
        heartRate = { heartRate },
        hrvWindows = { heartBeats?.let(::HeartBeatWindowSource) },
        motion = { motion },
        hrvClaim = claim,
        pressure = { pressure },
        spo2 = { spo2 },
        spo2Claim = spo2Claim,
        spo2Granted = { granted },
        ecg = { ecg },
        bodyComposition = { body },
        profile = { profile },
        additionalGranted = { granted },
        heartRateGranted = { granted },
        entries = entries,
        debug = DebugProtocol.off(),
        // Long enough for every fake's readings; short where a measurement waits its time out.
        limitMillis = { limit },
    )

    /** Thirty seconds at 500 Hz with an R wave every [beatMillis], in batches of a second; the first point says the electrodes are touched. */
    private fun ecgBatches(beatMillis: Double = 1000.0): List<List<EcgSample>> {
        val samples = List(30 * EcgHeartRate.SAMPLE_RATE_HZ) { k ->
            val t = k * 2.0
            val sinceBeat = (t - 100.0).mod(beatMillis)
            val r = kotlin.math.exp(-((sinceBeat / 8.0) * (sinceBeat / 8.0))) + kotlin.math.exp(-(((beatMillis - sinceBeat) / 8.0).let { it * it }))
            EcgSample((1.2 * r + 0.01 * kotlin.math.sin(t)).toFloat(), touching = if (k == 0) true else null)
        }
        return samples.chunked(EcgHeartRate.SAMPLE_RATE_HZ)
    }

    private fun bia(at: Long, status: Int = BodyCompositionRun.STATUS_OK, progress: Float = 0.5f, result: BodyComposition? = null) =
        BodyCompositionReading(BodyCompositionRun.Point(at, status, progress, result != null), result)

    private val composition = BodyComposition(19.6, leanMassKg = 64.3, bodyWaterKg = 47.1, skeletalMuscleKg = 36.0, basalMetabolicRateKcal = 1720.0)

    private fun events(spot: SpotMeasurements, kind: SpotKind): List<SpotEvent> =
        runBlocking { withTimeout(TIMEOUT_MILLIS) { spot.run(kind).toList() } }

    private fun stored(): List<WearLinkEntry> = store.since(WearMetrics.ENTRIES, 0, 100)

    private fun sample(bpm: Int): LiveHeartRate.Reading = LiveHeartRate.Reading.Sample(WearLinkProtocol.HeartRateSample(System.currentTimeMillis(), bpm))

    @Test
    fun `a heart rate settles, is shown as it comes and is kept as a measured entry`() {
        val sensor = FakeSensor(
            listOf(LiveHeartRate.Reading.NoContact, LiveHeartRate.Reading.Unreliable) + List(SpotRules.HEART_RATE_READINGS) { sample(64) },
        )

        val events = events(spot(heartRate = sensor), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Waiting(offBody = true), events[0])
        assertEquals(SpotEvent.Waiting(offBody = false), events[1])
        assertEquals(List(SpotRules.HEART_RATE_READINGS) { SpotEvent.Live(64.0) }, events.subList(2, events.size - 1))
        assertEquals(SpotEvent.Result(64.0), events.last())
        assertEquals(0, sensor.open)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.HEART_RATE, entry.type)
        assertEquals(64.0, entry.value, 0.0)
        assertTrue(entry.measured)
    }

    @Test
    fun `a watch off the wrist the whole time says so, and keeps nothing`() {
        val events = events(spot(heartRate = FakeSensor(listOf(LiveHeartRate.Reading.NoContact))), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Failed(SpotFailure.OFF_BODY), events.last())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `too few readings in the time is no signal`() {
        val events = events(spot(heartRate = FakeSensor(List(3) { sample(70) })), SpotKind.HEART_RATE)

        assertEquals(SpotEvent.Failed(SpotFailure.NO_SIGNAL), events.last())
    }

    @Test
    fun `without the grant, without the sensor or with a refused registration nothing is measured`() {
        val sensor = FakeSensor(List(SpotRules.HEART_RATE_READINGS) { sample(64) })
        granted = false
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)), events(spot(heartRate = sensor), SpotKind.HEART_RATE))
        assertEquals(0, sensor.listened)

        granted = true
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)), events(spot(), SpotKind.HRV))
        assertEquals(
            listOf(SpotEvent.Failed(SpotFailure.FAILED)),
            events(spot(pressure = FakeSensor(emptyList(), refuses = true)), SpotKind.AIR_PRESSURE),
        )
        assertTrue(stored().isEmpty())
    }

    /** Alternating intervals of 800 and 840 ms: an RMSSD of 40 ms. */
    private val beats = (0..SpotRules.HRV_MIN_CLEAN_BEATS + 1).runningFold(10_000.0) { at, index -> at + if (index % 2 == 0) 800.0 else 840.0 }

    /** Ten accelerometer readings a second for twenty seconds, [swing] m/s² either side of gravity. */
    private fun arm(swing: Double) = List(200) { Motion(timeMillis = it * 100L, magnitude = 9.81 + if (it % 2 == 0) swing else -swing) }

    @Test
    fun `an HRV is the RMSSD of its window, the pulse shown as it runs, with the heart rate sensor held for it`() {
        val beatSensor = FakeSensor(beats)
        val pulse = FakeSensor(List(3) { sample(72) })

        val events = events(spot(heartRate = pulse, heartBeats = beatSensor, motion = FakeSensor(arm(0.01))), SpotKind.HRV)

        assertEquals(List(3) { SpotEvent.Live(72.0) }, events.dropLast(1))
        assertEquals(40.0, (events.last() as SpotEvent.Result).value, 1e-9)
        assertEquals(1, pulse.listened)
        assertEquals(0, pulse.open)
        assertEquals(0, beatSensor.open)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.HRV, entry.type)
        assertEquals(40.0, entry.value, 1e-3)
        assertTrue(entry.measured)
        assertEquals(null, claim.holder)
    }

    @Test
    fun `an HRV from an arm that moved is not kept`() {
        val events = events(spot(heartBeats = FakeSensor(beats), motion = FakeSensor(arm(2.0))), SpotKind.HRV)

        assertEquals(SpotEvent.Failed(SpotFailure.MOVED), events.last())
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a spot HRV waits while the night's HRV window holds the sensor`() {
        val beatSensor = FakeSensor(beats)
        assertTrue(claim.take("night"))

        assertEquals(listOf(SpotEvent.Failed(SpotFailure.BUSY)), events(spot(heartBeats = beatSensor), SpotKind.HRV))
        assertEquals(0, beatSensor.listened)

        claim.release("night")
        assertTrue(events(spot(heartBeats = beatSensor), SpotKind.HRV).last() is SpotEvent.Result)
    }

    @Test
    fun `the air pressure is shown with its altitude, and not kept`() {
        val events = events(spot(pressure = FakeSensor(listOf(1000f, 1001f, 1002f, 1003f, 1004f, 1100f))), SpotKind.AIR_PRESSURE)

        val result = events.last() as SpotEvent.Result
        assertEquals(SpotRules.PRESSURE_READINGS, events.count { it is SpotEvent.Live })
        assertEquals(1002.0, result.value, 1e-9)
        assertEquals(AscentMinuteCounter.altitudeMetres(1002.0), result.secondary!!, 1e-9)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a blood oxygen is the tracker's first completed reading since the start, kept as a measured entry`() {
        val now = System.currentTimeMillis()
        val tracker = FakeSpo2(
            listOf(
                // The platform's last reading, from before the start: not this one.
                listOf(Spo2Reading(now - 60_000, percent = 91, bpm = 70, moving = false)),
                listOf(Spo2Reading(now, percent = null, bpm = 70, moving = false)),
                listOf(Spo2Reading(now + 13_000, percent = 97, bpm = 68, moving = false)),
            ),
        )

        val events = events(spot(spo2 = tracker), SpotKind.SPO2)

        assertEquals(SpotEvent.Result(97.0), events.last())
        assertEquals(1, tracker.started)
        val entry = stored().single()
        assertEquals(WearLinkEntry.Type.BLOOD_OXYGEN, entry.type)
        assertEquals(97.0, entry.value, 0.0)
        assertTrue(entry.measured)
        assertEquals(null, spo2Claim.holder)
    }

    @Test
    fun `a blood oxygen from a moving arm, without the grant or while the night's runs, is none`() {
        val moving = FakeSpo2(listOf(listOf(Spo2Reading(System.currentTimeMillis(), percent = null, bpm = 0, moving = true))))
        assertEquals(SpotEvent.Failed(SpotFailure.MOVED), events(spot(spo2 = moving), SpotKind.SPO2).last())

        assertTrue(spo2Claim.take("night"))
        val waiting = FakeSpo2(emptyList())
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.BUSY)), events(spot(spo2 = waiting), SpotKind.SPO2))
        assertEquals(0, waiting.started)
        spo2Claim.release("night")

        granted = false
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)), events(spot(spo2 = waiting), SpotKind.SPO2))
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.NOT_SUPPORTED)), events(spot(), SpotKind.SPO2))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `an ECG is thirty unbroken seconds, its trace shown as it grows, its rate and rhythm shown and nothing kept`() {
        val tracker = FakeTracker(listOf(ecgBatches()))

        val events = events(spot(ecg = tracker), SpotKind.ECG)

        val result = events.last() as SpotEvent.Result
        assertEquals(60.0, result.value, 1.0)
        assertEquals(EcgHeartRate.Rhythm.REGULAR, result.ecg?.rhythm)
        val traces = events.filterIsInstance<SpotEvent.Trace>()
        assertEquals(30, traces.size)
        assertEquals(1f, traces.last().recorded)
        assertEquals(3 * EcgHeartRate.SAMPLE_RATE_HZ, traces.last().millivolts.size)
        assertTrue("an ECG has no record", stored().isEmpty())
    }

    @Test
    fun `an ECG without the grant, or without enough contact in its time, is none`() {
        granted = false
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.PERMISSION_DENIED)), events(spot(ecg = FakeTracker(listOf(ecgBatches()))), SpotKind.ECG))
        granted = true
        val short = FakeTracker(listOf(ecgBatches().take(10)))
        assertEquals(SpotEvent.Failed(SpotFailure.NO_SIGNAL), events(spot(ecg = short), SpotKind.ECG).last())
    }

    @Test
    fun `a body composition shows its progress and is kept as its body fat, lean mass and body water`() {
        val now = System.currentTimeMillis()
        val tracker = FakeTracker(
            listOf(
                listOf(
                    listOf(bia(now, BodyCompositionRun.STATUS_NO_CALIBRATION), bia(now, progress = 0.3f)),
                    listOf(bia(now, BodyCompositionRun.STATUS_FINGERS_DRY), bia(now, progress = 1f, result = composition)),
                ),
            ),
        )

        val events = events(spot(body = tracker), SpotKind.BODY_COMPOSITION)

        assertEquals(SpotEvent.Result(19.6, body = composition), events.last())
        assertTrue(SpotEvent.Progress(0.3f, null) in events)
        assertTrue(SpotEvent.Progress(0.3f, SpotHint.FINGERS_DRY) in events)
        val kept = stored().associate { it.type to it.value }
        assertEquals(mapOf(WearLinkEntry.Type.BODY_FAT to 19.6, WearLinkEntry.Type.LEAN_BODY_MASS to 64.3, WearLinkEntry.Type.BODY_WATER to 47.1), kept)
        assertTrue(stored().all { it.measured })
    }

    @Test
    fun `a body composition whose tracker never got its calibration is started again`() {
        val now = System.currentTimeMillis()
        val lost = List(BodyCompositionRun.NO_CALIBRATION_POINTS) { bia(now, BodyCompositionRun.STATUS_NO_CALIBRATION) }
        // The second run's points come after its start.
        val later = now + 60_000
        val tracker = FakeTracker(listOf(listOf(lost), listOf(listOf(bia(later, progress = 1f, result = composition)))))

        val events = events(spot(body = tracker, limit = 3_000L), SpotKind.BODY_COMPOSITION)

        assertEquals(2, tracker.started)
        assertEquals(SpotEvent.Result(19.6, body = composition), events.last())
    }

    @Test
    fun `a body composition without the wearer's profile asks for it, and nothing starts`() {
        val tracker = FakeTracker<BodyCompositionReading>(emptyList())
        assertEquals(listOf(SpotEvent.Failed(SpotFailure.NO_PROFILE)), events(spot(body = tracker, profile = null), SpotKind.BODY_COMPOSITION))
        assertEquals(0, tracker.started)
    }

    @Test
    fun `a measurement holds a wake lock of its own while it runs, and lets it go`() {
        val sensor = FakeSensor(List(SpotRules.HEART_RATE_READINGS) { sample(64) })

        events(spot(heartRate = sensor), SpotKind.HEART_RATE)

        val lock = ShadowPowerManager.getLatestWakeLock()
        assertEquals("OpenWatch:spot", shadowOf(lock).tag)
        assertFalse(lock.isHeld)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
