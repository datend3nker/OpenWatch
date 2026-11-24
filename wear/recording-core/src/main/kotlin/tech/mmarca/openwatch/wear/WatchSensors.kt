package tech.mmarca.openwatch.wear

/**
 * What this watch has to measure with, read once from its sensor list and
 * its features: the facts the screens go by to offer a metric, a workout's
 * field or a spot measurement only where the watch can give it. Plain kinds,
 * so the decisions are tested off the watch; `WearGraph.sensors` reads them.
 */
data class WatchSensors(
    /** The heart rate sensor (`TYPE_HEART_RATE`). */
    val heartRate: Boolean,
    /** Single beats, each at its time (`TYPE_HEART_BEAT`): one source of a spot HRV. */
    val heartBeat: Boolean,
    /** Samsung's raw pulse signal (`com.samsung.sensor.hr_raw`), whose beats the night windows find: the other source of a spot HRV. */
    val rawPpg: Boolean,
    /** The step counter (`TYPE_STEP_COUNTER`). */
    val stepCounter: Boolean,
    /** The barometer (`TYPE_PRESSURE`): the height climbed, and the air pressure itself. */
    val barometer: Boolean,
    /** A GPS receiver (`FEATURE_LOCATION_GPS`): a workout's route, distance and pace. */
    val gps: Boolean,
    /**
     * The Samsung Health Platform's SpO2 tracker, the only source of a blood oxygen reading: the
     * red and infrared light are closed to ordinary apps.
     */
    val spo2: Boolean = false,
    /** The Samsung Health Platform lists its ECG tracker (code 5): a finger on the upper button. */
    val ecg: Boolean = false,
    /** The Samsung Health Platform lists its body composition tracker (code 5): two fingers on the buttons. */
    val bodyComposition: Boolean = false,
) {
    /** The spot measurements a wearer can start on this watch. */
    val spotKinds: Set<SpotKind>
        get() = buildSet {
            if (heartRate) add(SpotKind.HEART_RATE)
            if (heartBeat || rawPpg) add(SpotKind.HRV)
            if (barometer) add(SpotKind.AIR_PRESSURE)
            if (spo2) add(SpotKind.SPO2)
            if (ecg) add(SpotKind.ECG)
            if (bodyComposition) add(SpotKind.BODY_COMPOSITION)
        }

    companion object {
        /** A watch with every sensor, for a test or a preview. */
        val ALL = WatchSensors(
            heartRate = true, heartBeat = true, rawPpg = true, stepCounter = true, barometer = true, gps = true,
            spo2 = true, ecg = true, bodyComposition = true,
        )

        /** A watch with none. */
        val NONE = WatchSensors(heartRate = false, heartBeat = false, rawPpg = false, stepCounter = false, barometer = false, gps = false)
    }
}

/**
 * A reading the wearer starts by hand, by its plain kind; the screens give
 * each its name, icon and colour, and `SpotMeasurements` takes it
 * ([SpotRules]). The names are in the measurement's route, so they are
 * never renamed.
 */
enum class SpotKind(
    /** The longest the measurement runs, seconds. */
    val seconds: Int,
) {
    /** The pulse now, from the heart rate sensor: settled once enough readings agree. */
    HEART_RATE(30),

    /**
     * The RMSSD of three minutes of beats, from single beats or the raw pulse signal (or the
     * Samsung platform's own intervals, where the wearer chose them); it always runs the whole time.
     */
    HRV(180),

    /** The barometer, with the altitude it stands for at standard pressure. */
    AIR_PRESSURE(10),

    /**
     * Blood oxygen from the Samsung platform's on-demand tracker, as at night: red and infrared
     * light and a still arm, a reading after about 13 seconds on a Galaxy Watch7; a moving arm
     * spoils it.
     */
    SPO2(45),

    /**
     * The Samsung platform's ECG: [EcgRecording.RECORD_SECONDS] of unbroken contact, a finger on
     * the upper button, 500 samples a second; the longest time leaves room to find the contact
     * and start over once. Its heart rate and a description of its rhythm ([EcgHeartRate]) are
     * shown, not kept: Health Connect has no record for an ECG.
     */
    ECG(120),

    /**
     * The Samsung platform's body composition: two fingers of the other hand on the two buttons,
     * about fifteen seconds once they are on; the platform computes it from the impedance and the
     * wearer's profile, which the phone sends.
     */
    BODY_COMPOSITION(90),
}
