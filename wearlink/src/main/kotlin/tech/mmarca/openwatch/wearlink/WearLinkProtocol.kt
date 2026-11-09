package tech.mmarca.openwatch.wearlink

import java.util.UUID

/**
 * The line protocol between OpenWatch on the phone and the OpenWatch Wear OS
 * app, over one RFCOMM socket per request. Every line is UTF-8, space-separated
 * fields, `\n`-terminated. The phone sends one request line; the watch answers
 * with zero or more data lines and one terminating line, then both sides close.
 *
 * One source for both apps: `:wearlink` is compiled into `:app` and `:wear`.
 *
 * Every connection opens with one hello from the phone and one reply from the
 * watch; requests follow only after `OK`:
 * - `HELLO <version> <caps> <token> <name...>` — the phone's protocol version,
 *   its capabilities (`hr,sm` or `-`), the secret it holds for this watch
 *   (`WearLinkToken`) and its Bluetooth name, the rest of the line.
 * - `OK <version> <caps> <name...>` — the watch knows that token for this phone.
 * - `PENDING` — the watch does not hold this token for this phone and is asking
 *   the wearer; the phone tries again later. That is a new phone, or a known
 *   one presenting another token (a reinstalled or reset app, or an impostor:
 *   the wearer tells them apart).
 * - `UNAUTHORIZED <mismatch|blocked>` — the phone is blocked. `mismatch` is
 *   what an older watch app answers to another token; the phone still reads it.
 * - `VERSION <min> <max>` — the phone's version is outside what the watch speaks.
 * - `ERROR <bad_hello|bad_request|busy|internal>` — the watch could not serve.
 *
 * Requests:
 * - `PING` → `PONG`. Liveness only.
 * - `HR_SINCE <epochMillis> <limit>` → up to `limit` `HR` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`.
 * - `SM_SINCE <epochMillis> <limit>` → up to `limit` `SM` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`.
 * - `HV_SINCE <epochMillis> <limit>` → up to `limit` `HV` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`. The
 *   heart rate variability the watch measured at night; only a watch
 *   announcing `hv` serves it.
 * - `RR_SINCE <epochMillis> <limit>` → `RR` lines, as `HV_SINCE`; only a watch
 *   announcing `rr` serves it.
 * - `SK_SINCE <epochMillis> <limit>` → `SK` lines, as `HV_SINCE`; only a watch
 *   announcing `sk` serves it.
 * - `O2_SINCE <epochMillis> <limit>` → `O2` lines, as `HV_SINCE`; only a watch
 *   announcing `o2` serves it.
 * - `GET_PREF` → one `PREF <key> <value>` line per setting the watch holds,
 *   then `END <count> 0`. Only a watch announcing `pref` serves it.
 * - `SET_PREF <key> <value>` → `PREF_OK`, or `ERROR bad_request` for a key or
 *   value the watch does not take ([Preferences]).
 * - `WO_SINCE <epochMillis> <limit>` → up to `limit` workouts, oldest first, each
 *   started after `epochMillis` (exclusive): its `WO` header, then its `WP`
 *   pauses and `WR` route points (see [WearLinkWorkout]), then one `END
 *   <count> <more>` that counts workouts. Only a watch announcing `wo` serves it.
 * - `PUT_DAY …` → `DAY_OK` (`dy`), `LG_SINCE <epochMillis> <limit>` → `LG` lines (`lg`): see [WearLinkDaily].
 * - `PHONE_ACTIVE <epochMillis>` → `END 0 0`. The wearer unlocked the phone at
 *   that time: they are awake, so the watch ends a sleep hold it started by
 *   itself. Only a watch announcing `pa` serves it.
 * - `HR_LIVE <seconds>` → for up to `seconds` (at most [MAX_LIVE_SECONDS],
 *   less than the watch's idle timeout), one `HR` line as each reading
 *   arrives, then `END <count> 0`. Only a watch announcing `hl` serves it; a
 *   watch off the wrist answers `END 0 0` at once. Nothing is stored for it.
 *
 * Reply lines:
 * - `HR <epochMillis> <bpm>` — one heart rate sample.
 * - `HV <epochMillis> <rmssd10> <beats>` — one night-time window: its start,
 *   the RMSSD in tenths of a millisecond and the clean beats it rests on.
 * - `RR <epochMillis> <breaths10> <routes>` — the breathing rate of one
 *   night-time window, in tenths of a breath a minute, and how many routes
 *   (beat intervals, signal baseline) agreed on it.
 * - `O2 <epochMillis> <percent> <bpm|->` — one SpO2 spot measurement by the
 *   Samsung platform: its time, the saturation in percent, the heart rate.
 * - `SK <minuteMillis> <skin100> <ambient100>` — one minute of the Samsung
 *   platform's skin temperature, in hundredths of a degree Celsius.
 * - `SM <epochMillis> <kind> <offsetSeconds> <flags> <n> <mv10> <bpm|-> <hsd10|->
 *   <hn> <mx> <my> <mz> <sx> <sy> <sz> <zmin|-> <zmax|-> <zd10|->` — one sleep
 *   minute, the sleep pipeline's input on the phone, every field an integer
 *   and `-` meaning none. `docs/engineering/sleep-minute-features.md` defines
 *   each: `kind` is `R` (raw), `A` (awake) or `U` (unmeasurable); `flags` the
 *   bit field below; `n` the accelerometer samples; `mv10` the movement count
 *   times ten; `bpm`, `hsd10` and `hn` the minute's heart rate mean, standard
 *   deviation times ten and sample count; `mx..sz` the per-axis mean and
 *   standard deviation in milli-g; `zmin`, `zmax` the five-second z-angle
 *   extremes in degrees and `zd10` its mean change times ten.
 * - `END <count> <more>` — `count` data lines were sent; `more` is `1` when the
 *   limit cut the reply short and another request from the last line's time
 *   would return more.
 *
 * A line the receiver cannot parse is skipped, never fatal: an older peer may
 * send something newer.
 */
object WearLinkProtocol {

    /** Bumped when a change is not backward compatible; the hello negotiates it. */
    const val VERSION: Int = 4

    /** The oldest version this side still speaks. */
    const val MIN_VERSION: Int = 4

    /**
     * The RFCOMM service record the watch listens on and the phone connects to.
     * Distinct from the UUID OpenVitals used (4838d728-6e5a-4b95-a29d-a60032338301),
     * so an OpenVitals phone app never claims an OpenWatch watch, nor the reverse.
     */
    val SERVICE_UUID: UUID = UUID.fromString("cb9ccfd8-9e75-4773-ad00-08dd1df39c13")

    const val HELLO: String = "HELLO"
    const val OK: String = "OK"
    const val PENDING: String = "PENDING"
    const val UNAUTHORIZED: String = "UNAUTHORIZED"
    const val VERSION_WORD: String = "VERSION"
    const val ERROR: String = "ERROR"
    const val PING: String = "PING"
    const val PONG: String = "PONG"
    const val HEART_RATE_SINCE: String = "HR_SINCE"
    const val HEART_RATE: String = "HR"
    const val SLEEP_MINUTES_SINCE: String = "SM_SINCE"
    const val SLEEP_MINUTE: String = "SM"
    const val STEPS_SINCE: String = "ST_SINCE"
    const val STEP_MINUTE: String = "ST"
    const val ASCENT_SINCE: String = "AS_SINCE"
    const val ASCENT_MINUTE: String = "AS"
    const val HRV_SINCE: String = "HV_SINCE"
    const val HRV_WINDOW: String = "HV"
    const val RESPIRATORY_SINCE: String = "RR_SINCE"
    const val RESPIRATORY_WINDOW: String = "RR"
    const val SKIN_SINCE: String = "SK_SINCE"
    const val SPO2_SINCE: String = "O2_SINCE"
    const val SPO2_MEASUREMENT: String = "O2"
    const val SKIN_MINUTE: String = "SK"
    const val HEART_RATE_LIVE: String = "HR_LIVE"
    const val PHONE_ACTIVE: String = "PHONE_ACTIVE"
    const val WORKOUTS_SINCE: String = "WO_SINCE"
    const val SET_PREFERENCE: String = "SET_PREF"
    const val PREFERENCE_OK: String = "PREF_OK"
    const val GET_PREFERENCES: String = "GET_PREF"
    const val PREFERENCE: String = "PREF"
    const val END: String = "END"
    const val PUT_DAY: String = "PUT_DAY"
    const val DAY_OK: String = "DAY_OK"
    const val ENTRIES_SINCE: String = "LG_SINCE"
    const val ENTRY: String = "LG"

    /** Longer than this a line is a protocol violation; the reader closes rather than buffers. */
    const val MAX_LINE_LENGTH: Int = 512

    /** A device name longer than this is clipped on the wire. */
    const val MAX_NAME_LENGTH: Int = 48

    const val CAP_HEART_RATE: String = "hr"
    const val CAP_SLEEP_MINUTES: String = "sm"
    const val CAP_STEPS: String = "st"
    const val CAP_ASCENT: String = "as"
    const val CAP_LIVE_HEART_RATE: String = "hl"

    /** The watch measures heart rate variability at night and serves `HV_SINCE`. */
    const val CAP_HRV: String = "hv"

    /** The watch measures the breathing rate in its night windows and serves `RR_SINCE`. */
    const val CAP_RESPIRATORY_RATE: String = "rr"

    /** The watch records skin temperature from the Samsung platform and serves `SK_SINCE`. */
    const val CAP_SKIN_TEMPERATURE: String = "sk"

    /** The watch measures SpO2 at night with the Samsung platform and serves `O2_SINCE`. */
    const val CAP_SPO2: String = "o2"

    /** The watch takes `PHONE_ACTIVE`: the phone says when the wearer used it. */
    const val CAP_PHONE_ACTIVE: String = "pa"

    /** The watch records workouts and serves `WO_SINCE`. */
    const val CAP_WORKOUTS: String = "wo"

    /** The watch takes `PUT_DAY`: it shows the phone's figures for a day. */
    const val CAP_DAY_SUMMARY: String = "dy"

    /** The watch serves `LG_SINCE`: entries made on it. */
    const val CAP_ENTRIES: String = "lg"

    /** The watch takes `GET_PREF` and `SET_PREF`: the phone can show and change its settings. */
    const val CAP_PREFERENCES: String = "pref"

    /**
     * The settings the phone may read with `GET_PREF` and change with
     * `SET_PREF`: the watch's own ([WearSettings] on the watch). Opaque
     * tokens on the wire; the watch refuses an unknown key or value. The
     * read-only keys say what the watch can offer.
     */
    object Preferences {
        /** The heart rate outside bedtime mode. Values are `HeartRateInterval.wire` (`10`, `600`). */
        const val HEART_RATE_DAY: String = "hr_day"

        /** Where the heart rate comes from. [SENSOR] / [SAMSUNG]. */
        const val HEART_RATE_SOURCE: String = "hr_source"

        /** Where the night HRV windows' beats come from. [SENSOR] / [SAMSUNG]. */
        const val HRV_SOURCE: String = "hrv_source"

        /** How a sleep hold keeps the minutes whole. [LOCK] / [SYNCED]. */
        const val SLEEP_HOLD: String = "sleep_hold"

        /** Skin temperature at night from the Samsung platform. [OFF] / [ON]. */
        const val SKIN_TEMPERATURE: String = "skin"

        /** SpO2 spot measurements at night from the Samsung platform. [OFF] / [ON]. */
        const val SPO2: String = "spo2"

        /** The zones' maximum and resting heart rate from the profile, whole bpm; `0` for none. */
        const val HEART_RATE_MAX: String = "hr_max"
        const val HEART_RATE_REST: String = "hr_rest"

        /** Read only: whether the watch has the Samsung Health Platform. [OFF] / [ON]. */
        const val SAMSUNG_AVAILABLE: String = "samsung"

        /** Read only: whether the watch holds the permission its skin temperature needs. [OFF] / [ON]. */
        const val SKIN_GRANTED: String = "skin_granted"

        /** Read only: whether the watch holds the permission its SpO2 needs. [OFF] / [ON]. */
        const val SPO2_GRANTED: String = "spo2_granted"

        const val SENSOR: String = "sensor"
        const val SAMSUNG: String = "samsung"
        const val LOCK: String = "lock"
        const val SYNCED: String = "synced"
        const val OFF: String = "0"
        const val ON: String = "1"
    }
    const val NO_CAPABILITIES: String = "-"

    /** The phone's opening line. */
    data class Hello(val version: Int, val capabilities: Set<String>, val token: String, val name: String)

    enum class UnauthorizedReason(val word: String) {
        MISMATCH("mismatch"),
        BLOCKED("blocked"),
        ;

        companion object {
            fun fromWord(word: String): UnauthorizedReason? = entries.firstOrNull { it.word == word }
        }
    }

    enum class ErrorCode(val word: String) {
        BAD_HELLO("bad_hello"),
        BAD_REQUEST("bad_request"),
        BUSY("busy"),
        INTERNAL("internal"),
        ;

        companion object {
            fun fromWord(word: String): ErrorCode? = entries.firstOrNull { it.word == word }
        }
    }

    /** The watch's answer to a hello. */
    sealed class HelloReply {
        data class Ok(val version: Int, val capabilities: Set<String>, val name: String) : HelloReply()
        data object Pending : HelloReply()
        data class Unauthorized(val reason: UnauthorizedReason) : HelloReply()
        data class VersionMismatch(val min: Int, val max: Int) : HelloReply()
        data class Error(val code: ErrorCode) : HelloReply()
    }

    /** A request after the hello. */
    sealed class Request {
        data object Ping : Request()
        data class HeartRateSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class SleepMinutesSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class StepsSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class AscentSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class HrvSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class PhoneActive(val epochMillis: Long) : Request()
        data class WorkoutsSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class SetPreference(val key: String, val value: String) : Request()
        data object GetPreferences : Request()
        data class RespiratorySince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class SkinSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class Spo2Since(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class LiveHeartRate(val seconds: Int) : Request()
        data class PutDay(val day: DaySummary) : Request()
        data class EntriesSince(val sinceEpochMillis: Long, val limit: Int) : Request()
    }

    fun formatHello(hello: Hello): String =
        "$HELLO ${hello.version} ${formatCapabilities(hello.capabilities)} ${hello.token} ${cleanName(hello.name)}"

    /** Null unless [line] is a well-formed hello with a well-formed token. */
    fun parseHello(line: String): Hello? {
        val fields = fields(line)
        if (fields.size < 4 || fields[0] != HELLO) return null
        val version = fields[1].toIntOrNull() ?: return null
        val capabilities = parseCapabilities(fields[2]) ?: return null
        val token = fields[3]
        if (version < 1 || !WearLinkToken.isWellFormed(token)) return null
        return Hello(version, capabilities, token, cleanName(fields.drop(4).joinToString(" ")))
    }

    fun formatHelloReply(reply: HelloReply): String = when (reply) {
        is HelloReply.Ok -> "$OK ${reply.version} ${formatCapabilities(reply.capabilities)} ${cleanName(reply.name)}"
        HelloReply.Pending -> PENDING
        is HelloReply.Unauthorized -> "$UNAUTHORIZED ${reply.reason.word}"
        is HelloReply.VersionMismatch -> "$VERSION_WORD ${reply.min} ${reply.max}"
        is HelloReply.Error -> "$ERROR ${reply.code.word}"
    }

    /** Null unless [line] is a well-formed hello reply. */
    fun parseHelloReply(line: String): HelloReply? {
        val fields = fields(line)
        if (fields.isEmpty()) return null
        return when (fields[0]) {
            OK -> {
                if (fields.size < 3) return null
                val version = fields[1].toIntOrNull() ?: return null
                val capabilities = parseCapabilities(fields[2]) ?: return null
                HelloReply.Ok(version, capabilities, cleanName(fields.drop(3).joinToString(" ")))
            }
            PENDING -> if (fields.size == 1) HelloReply.Pending else null
            UNAUTHORIZED -> if (fields.size == 2) UnauthorizedReason.fromWord(fields[1])?.let(HelloReply::Unauthorized) else null
            VERSION_WORD -> {
                if (fields.size != 3) return null
                val min = fields[1].toIntOrNull() ?: return null
                val max = fields[2].toIntOrNull() ?: return null
                if (min < 1 || max < min) null else HelloReply.VersionMismatch(min, max)
            }
            ERROR -> if (fields.size == 2) ErrorCode.fromWord(fields[1])?.let(HelloReply::Error) else null
            else -> null
        }
    }

    fun formatError(code: ErrorCode): String = "$ERROR ${code.word}"

    fun formatRequest(request: Request): String = when (request) {
        Request.Ping -> PING
        is Request.HeartRateSince -> formatHeartRateRequest(request.sinceEpochMillis, request.limit)
        is Request.SleepMinutesSince -> formatSleepMinutesRequest(request.sinceEpochMillis, request.limit)
        is Request.StepsSince -> formatStepsRequest(request.sinceEpochMillis, request.limit)
        is Request.AscentSince -> formatAscentRequest(request.sinceEpochMillis, request.limit)
        is Request.HrvSince -> formatHrvRequest(request.sinceEpochMillis, request.limit)
        is Request.PhoneActive -> "$PHONE_ACTIVE ${request.epochMillis}"
        is Request.WorkoutsSince -> "$WORKOUTS_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_WORKOUTS_PER_REQUEST)}"
        is Request.SetPreference -> "$SET_PREFERENCE ${request.key} ${request.value}"
        Request.GetPreferences -> GET_PREFERENCES
        is Request.RespiratorySince -> "$RESPIRATORY_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_WINDOWS_PER_REQUEST)}"
        is Request.Spo2Since -> "$SPO2_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_WINDOWS_PER_REQUEST)}"
        is Request.SkinSince -> "$SKIN_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_SKIN_MINUTES_PER_REQUEST)}"
        is Request.LiveHeartRate -> "$HEART_RATE_LIVE ${request.seconds.coerceIn(1, MAX_LIVE_SECONDS)}"
        is Request.PutDay -> WearLinkDaily.formatPutDay(request.day)
        is Request.EntriesSince -> "$ENTRIES_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_WINDOWS_PER_REQUEST)}"
    }

    /** Null unless [line] is a well-formed request. */
    fun parseRequest(line: String): Request? {
        if (isPing(line)) return Request.Ping
        parseHeartRateRequest(line)?.let { return Request.HeartRateSince(it.sinceEpochMillis, it.limit) }
        parseSleepMinutesRequest(line)?.let { return Request.SleepMinutesSince(it.sinceEpochMillis, it.limit) }
        parseSinceRequest(line, STEPS_SINCE, MAX_STEP_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            return Request.StepsSince(since, limit)
        }
        parseSinceRequest(line, ASCENT_SINCE, MAX_ASCENT_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            return Request.AscentSince(since, limit)
        }
        parseSinceRequest(line, HRV_SINCE, MAX_HRV_WINDOWS_PER_REQUEST)?.let { (since, limit) ->
            return Request.HrvSince(since, limit)
        }
        parsePhoneActive(line)?.let { return it }
        parseSinceRequest(line, WORKOUTS_SINCE, MAX_WORKOUTS_PER_REQUEST)?.let { (since, limit) -> return Request.WorkoutsSince(since, limit) }
        if (fields(line) == listOf(GET_PREFERENCES)) return Request.GetPreferences
        parsePreferenceRequest(line)?.let { return it }
        parseSinceRequest(line, RESPIRATORY_SINCE, MAX_WINDOWS_PER_REQUEST)?.let { (since, limit) -> return Request.RespiratorySince(since, limit) }
        parseSinceRequest(line, SPO2_SINCE, MAX_WINDOWS_PER_REQUEST)?.let { (since, limit) -> return Request.Spo2Since(since, limit) }
        parseSinceRequest(line, SKIN_SINCE, MAX_SKIN_MINUTES_PER_REQUEST)?.let { (since, limit) -> return Request.SkinSince(since, limit) }
        parseLiveRequest(line)?.let { return Request.LiveHeartRate(it) }
        WearLinkDaily.parsePutDay(line)?.let { return Request.PutDay(it) }
        parseSinceRequest(line, ENTRIES_SINCE, MAX_WINDOWS_PER_REQUEST)?.let { (since, limit) -> return Request.EntriesSince(since, limit) }
        return null
    }

    private fun parseLiveRequest(line: String): Int? {
        val fields = fields(line)
        if (fields.size != 2 || fields[0] != HEART_RATE_LIVE) return null
        val seconds = fields[1].toIntOrNull() ?: return null
        return if (seconds < 1) null else seconds.coerceAtMost(MAX_LIVE_SECONDS)
    }

    private fun formatCapabilities(capabilities: Set<String>): String =
        if (capabilities.isEmpty()) NO_CAPABILITIES else capabilities.sorted().joinToString(",")

    private fun parseCapabilities(field: String): Set<String>? {
        if (field == NO_CAPABILITIES) return emptySet()
        val words = field.split(',')
        if (words.any { word -> word.isEmpty() || word.any { !it.isLetterOrDigit() && it != '_' } }) return null
        return words.toSet()
    }

    /** Printable characters only, every run of whitespace or control characters one space, at most [MAX_NAME_LENGTH]. */
    fun cleanName(name: String): String =
        name.map { if (it.isWhitespace() || it.code < 0x20 || it.code == 0x7f) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_NAME_LENGTH)

    /** The most samples one `HR_SINCE` reply carries. The phone pages past it. */
    const val MAX_SAMPLES_PER_REQUEST: Int = 2000

    /** The most minutes one `SM_SINCE` reply carries: a day and a half. The phone pages past it. */
    const val MAX_MINUTES_PER_REQUEST: Int = 2000

    /** The most step minutes one `ST_SINCE` reply carries. The phone pages past it. */
    const val MAX_STEP_MINUTES_PER_REQUEST: Int = 2000

    /** The longest one `HR_LIVE` runs: below the watch's 15-second idle timeout. The phone asks again. */
    const val MAX_LIVE_SECONDS: Int = 10

    /** The most ascent minutes one `AS_SINCE` reply carries. The phone pages past it. */
    const val MAX_ASCENT_MINUTES_PER_REQUEST: Int = 2000

    /** The most HRV windows one `HV_SINCE` reply carries: a week at 2 a night is far below it. */
    const val MAX_HRV_WINDOWS_PER_REQUEST: Int = 500

    /** The most workouts one `WO_SINCE` reply carries; each brings its own route, so the phone pages by workout. */
    const val MAX_WORKOUTS_PER_REQUEST: Int = 5

    /** The most breathing windows one `RR_SINCE` reply carries, as for HRV. */
    const val MAX_WINDOWS_PER_REQUEST: Int = 500

    /** The most skin temperature minutes one `SK_SINCE` reply carries. The phone pages past it. */
    const val MAX_SKIN_MINUTES_PER_REQUEST: Int = 2000

    /**
     * One night-time heart rate variability window, keyed on its start: the
     * RMSSD in tenths of a millisecond (integers on the wire) and the clean
     * beats it rests on.
     */
    data class HrvWindow(val epochMillis: Long, val rmssd10: Int, val beats: Int) {
        val rmssdMillis: Double get() = rmssd10 / 10.0
    }

    /** The breathing rate of one night-time window, keyed on its start; [routes] that agreed on it. */
    data class RespiratoryWindow(val epochMillis: Long, val breaths10: Int, val routes: Int) {
        val breathsPerMinute: Double get() = breaths10 / 10.0
    }

    /** One SpO2 spot measurement, keyed on its time; [bpm] is the heart rate the platform took with it. */
    data class Spo2Measurement(val epochMillis: Long, val percent: Int, val bpm: Int?)

    /** One minute of skin temperature, keyed on the minute's start, in hundredths of a degree Celsius. */
    data class SkinMinute(val epochMillis: Long, val skin100: Int, val ambient100: Int) {
        val skinCelsius: Double get() = skin100 / 100.0
    }

    data class HeartRateRequest(val sinceEpochMillis: Long, val limit: Int)

    data class HeartRateSample(val epochMillis: Long, val bpm: Int)

    data class SleepMinutesRequest(val sinceEpochMillis: Long, val limit: Int)

    /**
     * The steps the watch counted in one clock minute, keyed on the minute's
     * start. The watch rewrites a minute while late steps arrive. The count
     * only grows.
     */
    data class StepMinute(val epochMillis: Long, val steps: Int)

    /**
     * The height the watch's barometer climbed in one clock minute, in
     * decimetres, keyed on the minute's start. Elevators count too; the
     * phone keeps only minutes with steps and derives the floors from the
     * metres. The watch rewrites a minute while late readings arrive. The
     * count only grows.
     */
    data class AscentMinute(val epochMillis: Long, val decimetres: Int)

    /** What the watch said about one minute. The code is what goes on the wire. */
    enum class MinuteKind(val code: String) {
        RAW("R"),
        AWAKE("A"),
        UNMEASURABLE("U"),
        ;

        companion object {
            fun fromCode(code: String): MinuteKind? = entries.firstOrNull { it.code == code }
        }
    }

    /** Bits of the `flags` field. */
    const val FLAG_CHARGING: Int = 1
    const val FLAG_OFF_BODY: Int = 2
    const val FLAG_SCREEN_ON: Int = 4
    const val FLAG_HR_NO_CONTACT: Int = 8
    const val FLAG_HR_RECORDING: Int = 16
    const val FLAG_SPARSE: Int = 32

    /**
     * One minute of sleep input, as the wire carries it: integers, with the
     * tenths kept in the name. Null is `-`. [meanMilliG] and [sdMilliG] hold
     * x, y, z.
     */
    data class SleepMinute(
        val epochMillis: Long,
        val kind: MinuteKind,
        val offsetSeconds: Int,
        val flags: Int,
        val sampleCount: Int,
        val movement10: Int,
        val bpm: Int?,
        val heartRateSd10: Int?,
        val heartRateSamples: Int,
        val meanMilliG: IntArray,
        val sdMilliG: IntArray,
        val zAngleMin: Int?,
        val zAngleMax: Int?,
        val zAngleDelta10: Int?,
    ) {
        val movement: Float get() = movement10 / 10f
        val heartRateSd: Float? get() = heartRateSd10?.let { it / 10f }
        val zAngleDelta: Float? get() = zAngleDelta10?.let { it / 10f }

        fun hasFlag(flag: Int): Boolean = flags and flag != 0

        override fun equals(other: Any?): Boolean = other is SleepMinute && formatSleepMinute(this) == formatSleepMinute(other)

        override fun hashCode(): Int = formatSleepMinute(this).hashCode()
    }

    data class End(val count: Int, val more: Boolean)

    fun formatHeartRateRequest(sinceEpochMillis: Long, limit: Int): String =
        "$HEART_RATE_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_SAMPLES_PER_REQUEST)}"

    /** Null unless [line] is a well-formed `HR_SINCE` request. The limit is clamped. */
    fun parseHeartRateRequest(line: String): HeartRateRequest? =
        parseSinceRequest(line, HEART_RATE_SINCE, MAX_SAMPLES_PER_REQUEST)?.let { (since, limit) ->
            HeartRateRequest(since, limit)
        }

    fun formatSleepMinutesRequest(sinceEpochMillis: Long, limit: Int): String =
        "$SLEEP_MINUTES_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_MINUTES_PER_REQUEST)}"

    /** Null unless [line] is a well-formed `SM_SINCE` request. The limit is clamped. */
    fun parseSleepMinutesRequest(line: String): SleepMinutesRequest? =
        parseSinceRequest(line, SLEEP_MINUTES_SINCE, MAX_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            SleepMinutesRequest(since, limit)
        }

    fun formatStepsRequest(sinceEpochMillis: Long, limit: Int): String =
        "$STEPS_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_STEP_MINUTES_PER_REQUEST)}"

    fun formatStepMinute(minute: StepMinute): String = "$STEP_MINUTE ${minute.epochMillis} ${minute.steps}"

    /** Null unless [line] is a well-formed `ST` line: a minute start and a plausible count. */
    fun parseStepMinute(line: String): StepMinute? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != STEP_MINUTE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val steps = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || at % MINUTE_MILLIS != 0L || steps !in 1..MAX_STEPS_PER_MINUTE) return null
        return StepMinute(at, steps)
    }

    /** One setting the watch holds, as the reply to [GET_PREFERENCES] carries it. */
    fun formatPreference(key: String, value: String): String = "$PREFERENCE $key $value"

    /** Null unless [line] is a well-formed `PREF` line; the key and the value are the tokens `SET_PREF` takes. */
    fun parsePreference(line: String): Pair<String, String>? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != PREFERENCE) return null
        if (!isPreferenceToken(fields[1]) || !isPreferenceToken(fields[2])) return null
        return fields[1] to fields[2]
    }

    private fun parsePreferenceRequest(line: String): Request.SetPreference? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != SET_PREFERENCE) return null
        if (!isPreferenceToken(fields[1]) || !isPreferenceToken(fields[2])) return null
        return Request.SetPreference(fields[1], fields[2])
    }

    /** A preference key or value: the capability charset, so one line parses the same way. */
    private fun isPreferenceToken(token: String): Boolean =
        token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '_' }

    private fun parsePhoneActive(line: String): Request.PhoneActive? {
        val fields = fields(line)
        if (fields.size != 2 || fields[0] != PHONE_ACTIVE) return null
        val at = fields[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return Request.PhoneActive(at)
    }

    fun formatRespiratoryWindow(window: RespiratoryWindow): String =
        "$RESPIRATORY_WINDOW ${window.epochMillis} ${window.breaths10} ${window.routes}"

    /** Null unless [line] is a well-formed `RR` line: a time, a breathing rate of 6 to 30 a minute, one or two routes. */
    fun parseRespiratoryWindow(line: String): RespiratoryWindow? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != RESPIRATORY_WINDOW) return null
        val at = fields[1].toLongOrNull() ?: return null
        val breaths10 = fields[2].toIntOrNull() ?: return null
        val routes = fields[3].toIntOrNull() ?: return null
        if (at <= 0 || breaths10 !in MIN_BREATHS10..MAX_BREATHS10 || routes !in 1..2) return null
        return RespiratoryWindow(at, breaths10, routes)
    }

    fun formatSpo2(measurement: Spo2Measurement): String =
        "$SPO2_MEASUREMENT ${measurement.epochMillis} ${measurement.percent} ${measurement.bpm ?: NO_VALUE}"

    /** Null unless [line] is a well-formed `O2` line: a time, 70 to 100 percent, and a plausible heart rate or none. */
    fun parseSpo2(line: String): Spo2Measurement? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != SPO2_MEASUREMENT) return null
        val at = fields[1].toLongOrNull() ?: return null
        val percent = fields[2].toIntOrNull() ?: return null
        val bpm = if (fields[3] == NO_VALUE) null else fields[3].toIntOrNull() ?: return null
        if (at <= 0 || percent !in MIN_SPO2..MAX_SPO2 || (bpm != null && bpm !in MIN_BPM..MAX_BPM)) return null
        return Spo2Measurement(at, percent, bpm)
    }

    fun formatSkinMinute(minute: SkinMinute): String = "$SKIN_MINUTE ${minute.epochMillis} ${minute.skin100} ${minute.ambient100}"

    /** Null unless [line] is a well-formed `SK` line: a minute start and temperatures a wrist can have. */
    fun parseSkinMinute(line: String): SkinMinute? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != SKIN_MINUTE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val skin = fields[2].toIntOrNull() ?: return null
        val ambient = fields[3].toIntOrNull() ?: return null
        if (at <= 0 || at % MINUTE_MILLIS != 0L || skin !in MIN_SKIN100..MAX_SKIN100 || ambient !in MIN_AMBIENT100..MAX_AMBIENT100) return null
        return SkinMinute(at, skin, ambient)
    }

    fun formatHrvRequest(sinceEpochMillis: Long, limit: Int): String =
        "$HRV_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_HRV_WINDOWS_PER_REQUEST)}"

    fun formatHrvWindow(window: HrvWindow): String = "$HRV_WINDOW ${window.epochMillis} ${window.rmssd10} ${window.beats}"

    /** Null unless [line] is a well-formed `HV` line: a time, a plausible RMSSD and a plausible beat count. */
    fun parseHrvWindow(line: String): HrvWindow? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != HRV_WINDOW) return null
        val at = fields[1].toLongOrNull() ?: return null
        val rmssd10 = fields[2].toIntOrNull() ?: return null
        val beats = fields[3].toIntOrNull() ?: return null
        if (at <= 0 || rmssd10 !in MIN_RMSSD10..MAX_RMSSD10 || beats !in 1..MAX_HRV_BEATS) return null
        return HrvWindow(at, rmssd10, beats)
    }

    fun formatAscentRequest(sinceEpochMillis: Long, limit: Int): String =
        "$ASCENT_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_ASCENT_MINUTES_PER_REQUEST)}"

    fun formatAscentMinute(minute: AscentMinute): String = "$ASCENT_MINUTE ${minute.epochMillis} ${minute.decimetres}"

    /** Null unless [line] is a well-formed `AS` line: a minute start and a plausible ascent. */
    fun parseAscentMinute(line: String): AscentMinute? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != ASCENT_MINUTE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val decimetres = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || at % MINUTE_MILLIS != 0L || decimetres !in 1..MAX_ASCENT_DECIMETRES_PER_MINUTE) return null
        return AscentMinute(at, decimetres)
    }

    fun formatSample(epochMillis: Long, bpm: Int): String = "$HEART_RATE $epochMillis $bpm"

    /** Null unless [line] is a well-formed `HR` line with a plausible rate. */
    fun parseSample(line: String): HeartRateSample? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != HEART_RATE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val bpm = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || bpm !in MIN_BPM..MAX_BPM) return null
        return HeartRateSample(at, bpm)
    }

    fun formatSleepMinute(m: SleepMinute): String = buildString(96) {
        append(SLEEP_MINUTE).append(' ').append(m.epochMillis).append(' ').append(m.kind.code).append(' ')
        append(m.offsetSeconds).append(' ').append(m.flags).append(' ').append(m.sampleCount).append(' ')
        append(m.movement10).append(' ').append(m.bpm ?: NO_VALUE).append(' ').append(m.heartRateSd10 ?: NO_VALUE).append(' ')
        append(m.heartRateSamples)
        for (axis in 0 until 3) append(' ').append(m.meanMilliG[axis])
        for (axis in 0 until 3) append(' ').append(m.sdMilliG[axis])
        append(' ').append(m.zAngleMin ?: NO_VALUE).append(' ').append(m.zAngleMax ?: NO_VALUE)
        append(' ').append(m.zAngleDelta10 ?: NO_VALUE)
    }

    /** Null unless [line] is a well-formed `SM` line. A rate outside the plausible range reads as none. */
    fun parseSleepMinute(line: String): SleepMinute? {
        val f = fields(line)
        if (f.size != SLEEP_MINUTE_FIELDS || f[0] != SLEEP_MINUTE) return null
        val at = f[1].toLongOrNull() ?: return null
        val kind = MinuteKind.fromCode(f[2]) ?: return null
        val offset = f[3].toIntOrNull() ?: return null
        val flags = f[4].toIntOrNull() ?: return null
        val n = f[5].toIntOrNull() ?: return null
        val movement10 = f[6].toIntOrNull() ?: return null
        val bpm = optional(f[7]) ?: return null
        val heartRateSd10 = optional(f[8]) ?: return null
        val hn = f[9].toIntOrNull() ?: return null
        val mean = IntArray(3)
        val sd = IntArray(3)
        for (axis in 0 until 3) {
            mean[axis] = f[10 + axis].toIntOrNull() ?: return null
            sd[axis] = f[13 + axis].toIntOrNull() ?: return null
        }
        val zMin = optional(f[16]) ?: return null
        val zMax = optional(f[17]) ?: return null
        val zDelta10 = optional(f[18]) ?: return null
        if (at <= 0 || offset !in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS || flags < 0 || n < 0 || movement10 < 0 || hn < 0) return null
        return SleepMinute(
            epochMillis = at,
            kind = kind,
            offsetSeconds = offset,
            flags = flags,
            sampleCount = n,
            movement10 = movement10,
            bpm = bpm.value?.takeIf { it in MIN_BPM..MAX_BPM },
            heartRateSd10 = heartRateSd10.value,
            heartRateSamples = hn,
            meanMilliG = mean,
            sdMilliG = sd,
            zAngleMin = zMin.value,
            zAngleMax = zMax.value,
            zAngleDelta10 = zDelta10.value,
        )
    }

    /** An integer or `-`. Null for anything else, so the caller can reject the line. */
    private class Optional(val value: Int?)

    private fun optional(field: String): Optional? =
        if (field == NO_VALUE) Optional(null) else field.toIntOrNull()?.let { Optional(it) }

    private const val SLEEP_MINUTE_FIELDS = 19

    fun formatEnd(count: Int, more: Boolean): String = "$END $count ${if (more) 1 else 0}"

    /** Null unless [line] is a well-formed `END` line. */
    fun parseEnd(line: String): End? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != END) return null
        val count = fields[1].toIntOrNull() ?: return null
        val more = when (fields[2]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        if (count < 0) return null
        return End(count, more)
    }

    /** True when [line] is the ping request. */
    fun isPing(line: String): Boolean = line.trim() == PING

    private fun parseSinceRequest(line: String, word: String, maxLimit: Int): Pair<Long, Int>? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != word) return null
        val since = fields[1].toLongOrNull() ?: return null
        val limit = fields[2].toIntOrNull() ?: return null
        if (since < 0 || limit < 1) return null
        return since to limit.coerceAtMost(maxLimit)
    }

    private fun fields(line: String): List<String> = line.trim().split(' ').filter { it.isNotEmpty() }

    private const val NO_VALUE = "-"

    /** Eighteen hours: the widest UTC offset there is. */
    private const val MAX_OFFSET_SECONDS = 18 * 3600

    private const val MINUTE_MILLIS = 60_000L

    /** Sprinting is about 200 steps a minute. More means the counter jumped. */
    const val MAX_STEPS_PER_MINUTE: Int = 400

    /** A fast elevator climbs about 600 m a minute. More means the barometer jumped. */
    const val MAX_ASCENT_DECIMETRES_PER_MINUTE: Int = 9000

    /** An RMSSD of 0.1 to 300 ms, in tenths. Outside it the detector failed, not a heart. */
    const val MIN_RMSSD10: Int = 1
    const val MAX_RMSSD10: Int = 3000

    /** 6 to 30 breaths a minute, in tenths: the band the estimate reads. */
    const val MIN_BREATHS10: Int = 60
    const val MAX_BREATHS10: Int = 300

    /** Below 70 percent the reading is a failed measurement, not a sleeper. */
    const val MIN_SPO2: Int = 70
    const val MAX_SPO2: Int = 100

    /** A wrist reads 20 to 42 °C; the air around it -20 to 50 °C. In hundredths. */
    const val MIN_SKIN100: Int = 2000
    const val MAX_SKIN100: Int = 4200
    const val MIN_AMBIENT100: Int = -2000
    const val MAX_AMBIENT100: Int = 5000

    /** A window of a few minutes holds under a thousand beats. */
    const val MAX_HRV_BEATS: Int = 1000

    /** Outside this range the sensor is reporting noise, not a heart. */
    const val MIN_BPM: Int = 25
    const val MAX_BPM: Int = 250
}
