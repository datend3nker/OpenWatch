package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WearLinkWorkout

/**
 * What a workout needs to be saved after the app was killed: written every
 * few seconds while it runs, as one line in the store's state table. The
 * route is not in it; each point is stored as it arrives.
 *
 * `v1 <sportId> <start> <saved> <gps 0|1> <distance|-> <steps|-> <ascentDm|-> <pausedSince|-> <pauses>`
 * where `pauses` is `start-end` pairs joined by `,`, or `-`.
 */
data class WorkoutCheckpoint(
    val sportId: String,
    val startMillis: Long,
    val savedMillis: Long,
    val useGps: Boolean,
    val distanceMetres: Int?,
    val steps: Int?,
    val ascentDecimetres: Int?,
    val pauses: List<WearLinkWorkout.Pause>,
    val pausedSince: Long?,
) {
    fun encode(): String = listOf(
        VERSION,
        sportId,
        startMillis,
        savedMillis,
        if (useGps) 1 else 0,
        distanceMetres ?: NONE,
        steps ?: NONE,
        ascentDecimetres ?: NONE,
        pausedSince ?: NONE,
        if (pauses.isEmpty()) NONE else pauses.joinToString(",") { "${it.startMillis}-${it.endMillis}" },
    ).joinToString(" ")

    companion object {
        private const val VERSION = "v1"
        private const val NONE = "-"
        private const val FIELDS = 10

        /** Null for text this build cannot read. */
        fun decode(text: String?): WorkoutCheckpoint? = runCatching { parse(text?.trim()?.split(' ').orEmpty()) }.getOrNull()

        private fun parse(f: List<String>): WorkoutCheckpoint? {
            if (f.size != FIELDS || f[0] != VERSION) return null
            val pauses = if (f[9] == NONE) {
                emptyList()
            } else {
                f[9].split(',').map { pair ->
                    val (start, end) = pair.split('-')
                    WearLinkWorkout.Pause(start.toLong(), end.toLong())
                }
            }
            return WorkoutCheckpoint(
                sportId = f[1],
                startMillis = f[2].toLong(),
                savedMillis = f[3].toLong(),
                useGps = f[4] == "1",
                distanceMetres = f[5].toIntOrNone(),
                steps = f[6].toIntOrNone(),
                ascentDecimetres = f[7].toIntOrNone(),
                pausedSince = f[8].takeIf { it != NONE }?.toLong(),
                pauses = pauses,
            )
        }

        private fun String.toIntOrNone(): Int? = if (this == NONE) null else toInt()
    }
}
