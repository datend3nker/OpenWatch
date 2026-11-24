package tech.mmarca.openwatch.wear

/**
 * One body composition measurement as the Samsung platform's tracker reports it, point by point:
 * how far it has come, what its contact lacks, its result, and when the tracker has to be started
 * again. Pure, so the rules are tested off the watch; `SpotMeasurements` feeds it the points.
 *
 * The first point of every run reports "no calibration" ([STATUS_NO_CALIBRATION]): the platform's
 * library handles it before it sets the calibration the sensor sends with it. That is no failure.
 * The library loads on its first use in the process, which can take longer than the sensor needs
 * to send its calibration once; that run then reports it for every point (seen on a Galaxy
 * Watch7). After [NO_CALIBRATION_POINTS] such points in a row the tracker is started again, up to
 * [MAX_RESTARTS] times: the library is loaded by then.
 */
class BodyCompositionRun {

    /** One point: the platform's status, the progress to 1, and whether it carries the result. */
    class Point(val epochMillis: Long, val status: Int, val progress: Float, val isResult: Boolean)

    sealed interface Step {
        /** Nothing new to tell. */
        data object Continue : Step

        /** How far it has come, and what the contact lacks (null for nothing). */
        data class Progress(val share: Float, val hint: SpotHint?) : Step

        /** Start the tracker again: its library had no calibration. */
        data object Restart : Step

        /** This point is the result. */
        data object Result : Step

        /** The calibration never came, even after the restarts. */
        data object NoCalibration : Step
    }

    private var restarts = 0
    private var startedAt = 0L
    private var calibrated = false
    private var noCalibrationPoints = 0
    private var share = 0f

    /** The tracker was started (again) at [epochMillis]. */
    fun started(epochMillis: Long) {
        startedAt = epochMillis
        calibrated = false
        noCalibrationPoints = 0
    }

    fun next(point: Point): Step {
        // After a restart, a point the run before left behind; the first run takes every point.
        if (restarts > 0 && point.epochMillis in 1 until startedAt) return Step.Continue
        if (point.status == STATUS_NO_CALIBRATION) {
            // The first point always; only a run that never gets past it has lost its calibration.
            if (calibrated || ++noCalibrationPoints < NO_CALIBRATION_POINTS) return Step.Continue
            if (restarts >= MAX_RESTARTS) return Step.NoCalibration
            restarts++
            return Step.Restart
        }
        calibrated = true
        if (point.status != STATUS_OK) return Step.Progress(share, hintOf(point.status))
        if (!point.progress.isNaN()) share = point.progress.coerceIn(0f, 1f)
        if (point.isResult) return Step.Result
        return Step.Progress(share, null)
    }

    companion object {
        const val STATUS_OK = 0

        /** "NV_NG": the platform's body composition library has no calibration (yet). */
        const val STATUS_NO_CALIBRATION = 2
        const val STATUS_WRIST_DETACHED = 4
        const val STATUS_FINGERS_DETACHED = 9
        const val STATUS_WRIST_LOOSE = 10
        const val STATUS_FINGERS_DRY = 11
        const val STATUS_HANDS_TOUCH = 14
        const val STATUS_FRAME_TOUCHED = 15

        /** Once the library is loaded the first restart works; a second is a margin. */
        const val MAX_RESTARTS = 2

        /** About a second of points: the first one is always without calibration, a lost one stays so. */
        const val NO_CALIBRATION_POINTS = 8

        /** What the wearer is told for a status the platform reports while it measures; null for one with no advice. */
        fun hintOf(status: Int): SpotHint? = when (status) {
            STATUS_WRIST_DETACHED, STATUS_WRIST_LOOSE -> SpotHint.WRIST
            STATUS_FINGERS_DETACHED -> SpotHint.FINGERS
            STATUS_FINGERS_DRY -> SpotHint.FINGERS_DRY
            STATUS_HANDS_TOUCH -> SpotHint.HANDS_TOUCH
            STATUS_FRAME_TOUCHED -> SpotHint.FRAME_TOUCHED
            else -> null
        }
    }
}
