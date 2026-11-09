package tech.mmarca.openwatch.wear.features.measure

import kotlin.math.sqrt

/**
 * Whether the arm stayed still through a spot HRV measurement, judged from the accelerometer's
 * magnitude in blocks of [BLOCK_MILLIS]. A block moves when its spread of the magnitude passes
 * [MAX_SPREAD]; the window is spoiled when more than [MAX_MOVING_SHARE] of its blocks move.
 * Both numbers are judgement, not fitted: a resting wrist reads well under 0.1 m/s², typing or a
 * wave of the hand well over 0.5.
 */
internal object Stillness {

    const val BLOCK_MILLIS = 5_000L
    const val MAX_SPREAD = 0.3
    const val MAX_MOVING_SHARE = 0.2

    /** True when the window moved too much. With no readings it cannot be judged and counts as moved. */
    fun moved(timesMillis: LongArray, magnitudes: DoubleArray): Boolean {
        if (timesMillis.isEmpty() || timesMillis.size != magnitudes.size) return true
        val start = timesMillis.first()
        val blocks = magnitudes.indices.groupBy { (timesMillis[it] - start) / BLOCK_MILLIS }.values
        // A block with a few readings says little about the spread; ignore it.
        val judged = blocks.filter { it.size >= MIN_READINGS }
        if (judged.isEmpty()) return true
        val moving = judged.count { block -> spread(block.map { magnitudes[it] }) > MAX_SPREAD }
        return moving > MAX_MOVING_SHARE * judged.size
    }

    private fun spread(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private const val MIN_READINGS = 10
}
