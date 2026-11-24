package tech.mmarca.openwatch.wear.samsung

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executor
import tech.mmarca.healthcalculations.signal.IbiPoint
import tech.mmarca.openwatch.wear.SkinReading
import tech.mmarca.openwatch.wear.Spo2Reading

/** One point as the platform sends it: a time and, per value, its int and its float. */
internal class RawPoint(val epochMillis: Long, val ints: IntArray, val floats: FloatArray)

/**
 * The trackers of the Samsung Health Platform
 * (`com.samsung.android.service.health`), spoken to over Binder with no
 * vendor library. Written from the protocol observed on a Galaxy Watch7;
 * `docs/engineering/samsung-health-and-privacy.md` records it, and why the
 * Samsung Health Sensor SDK itself never enters this repository.
 *
 * Outbound, on the service token: 1 create(appId, type, profile?, exercise?),
 * 2 setListener(appId, type, binder), 3 unsetListener(appId, type),
 * 4 flush(appId, type), 5 the supported types (a string list in the reply),
 * 8 setPpgType(appId, type, colours). 6 and 7 carry an exercise's state and
 * data to the sweat loss tracker and are not spoken here. Inbound, on the listener token: 1 data (a typed list
 * of points), 2 error(int), 3 flush done. A point is a time and a generic
 * list of values, each an (int, float, long).
 *
 * The platform keeps one client per package with one tracker per type
 * (`WearTrackerService.clientsMap`, `ClientHealthTracking`), so this app
 * holds one binding and adds and removes types on it ([track], [untrack]).
 * A callback names no type, so each type has its own listener binder.
 * Outside the platform's Developer Mode the platform asks Samsung whether
 * this package may use it before it sends data; the app cannot read the
 * mode, so every Samsung source is opt-in and the setting says so.
 *
 * Every callback is posted to [handler], and every method is called on its thread.
 */
internal class SamsungHealthPlatform(
    private val context: Context,
    private val handler: Handler,
    /** The binding itself failed: the platform went away. Every tracker is affected. */
    private val onError: () -> Unit,
) {
    /**
     * What one tracker type delivers to; [onError] is that tracker's own failure (a refused permission, a platform error).
     * [profile] is what the body composition tracker computes with (it assumes a default person without one);
     * [ppgColours] is what the continuous PPG tracker switches on ([PPG_GREEN], [PPG_RED], [PPG_IR]).
     */
    class Tracker(
        val onPoints: (List<RawPoint>) -> Unit,
        val onFlushed: () -> Unit,
        val onError: () -> Unit,
        val profile: UserProfile? = null,
        val ppgColours: List<String> = emptyList(),
    )

    /** The wearer as the platform's `TrackerUserProfile` takes them: two floats, then two ints. */
    class UserProfile(val heightCm: Float, val weightKg: Float, val male: Boolean, val ageYears: Int)

    private var service: IBinder? = null
    private var bound = false
    private val trackers = LinkedHashMap<String, Pair<Tracker, Binder>>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            for ((type, entry) in trackers) startTracker(type, entry.second)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onError()
        }
    }

    /** Binds. False when the platform is not there or refused the bind. */
    fun start(): Boolean {
        if (bound) return true
        bound = try {
            context.bindService(bindIntent(), Context.BIND_AUTO_CREATE, Executor { handler.post(it) }, connection)
        } catch (_: SecurityException) {
            false
        }
        if (!bound) Log.w(TAG, "Bind refused")
        return bound
    }

    /** Starts [type] (now, or once connected). A type already tracked is replaced. */
    fun track(type: String, tracker: Tracker) {
        untrack(type)
        val listener = listenerFor(tracker)
        trackers[type] = tracker to listener
        if (service != null) startTracker(type, listener)
    }

    fun untrack(type: String) {
        trackers.remove(type) ?: return
        try {
            if (service != null) call(TX_UNSET_LISTENER) { writeString(appId()); writeString(type) }
        } catch (_: RemoteException) {
            // The platform is gone; there is nothing left to unset.
        } catch (_: RuntimeException) {
            // As above.
        }
    }

    val trackedTypes: Set<String>
        get() = trackers.keys

    /** Asks for the points of [type] the platform still holds; its tracker's onFlushed follows. False when not connected. */
    fun flush(type: String): Boolean = try {
        service != null && type in trackers && run { call(TX_FLUSH) { writeString(appId()); writeString(type) }; true }
    } catch (_: RemoteException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun stop() {
        for (type in trackers.keys.toList()) untrack(type)
        if (bound) context.unbindService(connection)
        bound = false
        service = null
    }

    private fun startTracker(type: String, listener: Binder) {
        val tracker = trackers[type]?.first
        try {
            val profile = tracker?.profile
            call(TX_CREATE) {
                writeString(appId())
                writeString(type)
                // A typed object: a presence flag, then its fields.
                if (profile == null) {
                    writeInt(0)
                } else {
                    writeInt(1)
                    writeFloat(profile.heightCm)
                    writeFloat(profile.weightKg)
                    writeInt(if (profile.male) GENDER_MALE else GENDER_FEMALE)
                    writeInt(profile.ageYears)
                }
                writeString(null)
            }
            // The colours are read when the listener is set, so they go first.
            val colours = tracker?.ppgColours.orEmpty()
            if (colours.isNotEmpty()) call(TX_SET_PPG_TYPE) { writeString(appId()); writeString(type); writeStringList(colours) }
            call(TX_SET_LISTENER) { writeString(appId()); writeString(type); writeStrongBinder(listener) }
        } catch (e: RemoteException) {
            Log.w(TAG, "Tracker refused", e)
            tracker?.onError?.invoke()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Tracker refused", e)
            tracker?.onError?.invoke()
        }
    }

    private fun listenerFor(tracker: Tracker) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_TOKEN)
                return true
            }
            if (code !in CB_DATA..CB_FLUSH_DONE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(LISTENER_TOKEN)
            try {
                when (code) {
                    CB_DATA -> {
                        val points = readPoints(data)
                        handler.post { tracker.onPoints(points) }
                    }
                    CB_ERROR -> {
                        Log.w(TAG, "Platform error ${data.readInt()}")
                        handler.post(tracker.onError)
                    }
                    else -> handler.post(tracker.onFlushed)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unreadable callback", e)
                handler.post(tracker.onError)
            }
            reply?.writeNoException()
            return true
        }
    }

    private fun appId(): String = context.packageName

    private fun call(code: Int, args: Parcel.() -> Unit) {
        val target = service ?: throw RemoteException("not connected")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SERVICE_TOKEN)
            data.args()
            target.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    companion object {
        private const val TAG = "SamsungHealthPlatform"
        private const val SERVICE_PACKAGE = "com.samsung.android.service.health"
        private const val BIND_ACTION = "com.samsung.android.service.health.wear.sts.ACTION_SERVICE"
        private const val SERVICE_TOKEN = "com.samsung.android.service.health.tracking.IClientEventListener"
        private const val LISTENER_TOKEN = "com.samsung.android.service.health.tracking.IServiceEventListener"
        const val TRACKER_HEART_RATE = "HEART_RATE"
        const val TRACKER_SKIN_TEMPERATURE = "SKIN_TEMPERATURE_CONTINUOUS"
        const val TRACKER_SPO2 = "SPO2"
        const val TRACKER_ECG = "ECG"
        const val TRACKER_BIA = "BIA"
        const val TRACKER_PPG = "PPG_CONTINUOUS"
        const val PPG_GREEN = "GREEN"
        const val PPG_RED = "RED"
        const val PPG_IR = "IR"
        private const val TX_CREATE = 1
        private const val TX_SET_LISTENER = 2
        private const val TX_UNSET_LISTENER = 3
        private const val TX_FLUSH = 4
        private const val TX_SUPPORTED_TYPES = 5
        private const val TX_SET_PPG_TYPE = 8
        private const val GENDER_FEMALE = 0
        private const val GENDER_MALE = 1
        private const val CB_DATA = 1
        private const val CB_ERROR = 2
        private const val CB_FLUSH_DONE = 3
        private const val VAL_PARCELABLE = 4

        private fun bindIntent() = Intent(BIND_ACTION).setPackage(SERVICE_PACKAGE)

        /**
         * Bounds on what one callback may claim: far above a flush's points, and
         * far above a first heart rate point, which carried 535 intervals (1 074
         * values) on a Galaxy Watch7. Below them, the bytes the parcel really holds
         * bound every count: a point takes at least its presence flag, a value at
         * least [MIN_VALUE_BYTES].
         */
        internal const val MAX_POINTS = 100_000
        internal const val MAX_VALUES = 8_192

        /** A value's type, its class name (a null one is a length alone), its int, float and long. */
        private const val MIN_VALUE_BYTES = 4 + 4 + 4 + 4 + 8
        private const val INT_BYTES = 4

        /** An app the system image ships, updated or not: what Samsung's tracking service is. */
        internal fun isSystemApp(flags: Int): Boolean =
            flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        internal fun readPoints(p: Parcel): List<RawPoint> = readPoints(ParcelInput.of(p))

        /**
         * A typed list: a count, then per item a presence flag and the point. The counts come from
         * the other process, so an absurd one, or one the bytes left cannot hold, fails the read
         * before anything is allocated for it.
         */
        internal fun readPoints(p: ParcelInput): List<RawPoint> {
            val points = ArrayList<RawPoint>()
            val size = p.readInt()
            check(size in 0..MAX_POINTS && size <= p.available / INT_BYTES) { "point count $size" }
            repeat(size) {
                if (p.readInt() == 0) return@repeat
                val timestamp = p.readLong()
                val count = p.readInt()
                check(count in 0..MAX_VALUES && count <= p.available / MIN_VALUE_BYTES) { "value count $count" }
                val ints = IntArray(count)
                val floats = FloatArray(count)
                for (v in 0 until count) {
                    val type = p.readInt()
                    check(type == VAL_PARCELABLE) { "value type $type" }
                    val afterValue = readOptionalLengthPrefix(p)
                    p.readString() // the value's class name
                    ints[v] = p.readInt()
                    floats[v] = p.readFloat()
                    p.readLong()
                    if (afterValue >= 0) p.position = afterValue
                }
                points += RawPoint(timestamp, ints, floats)
            }
            return points
        }


        /** The platform's version code, for the debug protocol's device line; null where it is not installed. */
        fun versionCode(context: Context): Long? =
            try {
                context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0).longVersionCode
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

        /** Whether this watch has the platform's tracking service, from its system image: Samsung watches only. */
        fun isInstalled(context: Context): Boolean =
            context.packageManager.queryIntentServices(bindIntent(), 0)
                .any { resolved -> resolved.serviceInfo?.applicationInfo?.let { isSystemApp(it.flags) } == true }

        /**
         * Asks the platform which tracker types this watch has (code 5) and
         * hands the answer to [onResult] on [handler]: null when the platform
         * is not there or did not answer. The call names no client and starts
         * no tracker, so it has a short binding of its own.
         */
        fun supportedTypes(context: Context, handler: Handler, onResult: (Set<String>?) -> Unit) {
            val app = context.applicationContext
            val connection = object : ServiceConnection {
                private var answered = false

                override fun onServiceConnected(name: ComponentName, binder: IBinder) = answer(readSupportedTypes(binder))

                override fun onServiceDisconnected(name: ComponentName) = answer(null)

                private fun answer(types: Set<String>?) {
                    if (answered) return
                    answered = true
                    app.unbindService(this)
                    onResult(types)
                }
            }
            val bound = try {
                app.bindService(bindIntent(), Context.BIND_AUTO_CREATE, Executor { handler.post(it) }, connection)
            } catch (_: SecurityException) {
                false
            }
            if (!bound) handler.post { onResult(null) }
        }

        private fun readSupportedTypes(service: IBinder): Set<String>? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(SERVICE_TOKEN)
                service.transact(TX_SUPPORTED_TYPES, data, reply, 0)
                reply.readException()
                reply.createStringArrayList()?.toSet()
            } catch (e: RemoteException) {
                Log.w(TAG, "Types not read", e)
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "Types not read", e)
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        /**
         * Newer platforms write a length before a parcelable inside a generic
         * list; older ones do not. Peek: if the next int is a plausible length
         * followed by a class name, consume it and return where the value
         * ends; otherwise rewind and return -1. The length is compared with
         * what is left, so a huge one cannot overflow past the check.
         */
        private fun readOptionalLengthPrefix(p: ParcelInput): Int {
            val start = p.position
            val length = p.readInt()
            val afterLength = p.position
            if (length > 0 && length <= p.size - afterLength) {
                val name = p.readString()
                if (name != null && name.indexOf('.') > 0) {
                    p.position = afterLength
                    return afterLength + length
                }
            }
            p.position = start
            return -1
        }
    }
}

/**
 * What [SamsungHealthPlatform.readPoints] reads a callback from: the [Parcel]
 * the platform sent, or, in a test, the bytes of a recorded one. Positions and
 * sizes are in bytes.
 */
internal interface ParcelInput {
    fun readInt(): Int
    fun readLong(): Long
    fun readFloat(): Float
    fun readString(): String?
    var position: Int
    val size: Int

    /** The bytes left to read. */
    val available: Int
        get() = (size - position).coerceAtLeast(0)

    companion object {
        fun of(parcel: Parcel): ParcelInput = object : ParcelInput {
            override fun readInt(): Int = parcel.readInt()
            override fun readLong(): Long = parcel.readLong()
            override fun readFloat(): Float = parcel.readFloat()
            override fun readString(): String? = parcel.readString()
            override var position: Int
                get() = parcel.dataPosition()
                set(value) = parcel.setDataPosition(value)
            override val size: Int
                get() = parcel.dataSize()
        }
    }
}

/** A heart rate point: value 0 the rate, 2 its status, 3 the interval count, then (interval, status) pairs from 4. */
internal fun RawPoint.toHeartRatePoint(): IbiPoint {
    val pairs = if (ints.size > 3) minOf(ints[3], (ints.size - 4) / 2).coerceAtLeast(0) else 0
    return IbiPoint(
        epochMillis = epochMillis,
        intervalsMillis = IntArray(pairs) { ints[4 + 2 * it] },
        statuses = IntArray(pairs) { ints[4 + 2 * it + 1] },
        bpm = ints.getOrElse(0) { 0 },
        heartRateStatus = ints.getOrElse(2) { 0 },
    )
}

/** The skin temperature tracker's status for a measured point. */
private const val SKIN_MEASURED = 0

/** A skin temperature point: the object temperature at float 0, the ambient at float 1, the status at int 2. */
internal fun RawPoint.toSkinReading(): SkinReading = SkinReading(
    epochMillis,
    skinCelsius = floats.getOrElse(0) { Float.NaN },
    ambientCelsius = floats.getOrElse(1) { Float.NaN },
    measured = ints.getOrElse(2) { SKIN_MEASURED } == SKIN_MEASURED,
)

/** The SpO2 tracker's statuses, as seen on a Galaxy Watch7: -6 after the measurement ended, 0 while it runs. */
internal const val SPO2_COMPLETED = 2
internal const val SPO2_MOVING = -4

/** An SpO2 point: the status at int 0, the percentage at 1, the heart rate at 2. A percentage counts only once completed. */
internal fun RawPoint.toSpo2Reading(): Spo2Reading {
    val status = ints.getOrElse(0) { 0 }
    val percent = ints.getOrElse(1) { 0 }
    return Spo2Reading(
        epochMillis,
        percent = percent.takeIf { status == SPO2_COMPLETED && it in 70..100 },
        bpm = ints.getOrElse(2) { 0 },
        moving = status == SPO2_MOVING,
    )
}

/**
 * One ECG sample, 500 a second: the voltage at float 0. The platform sends
 * them in groups whose first point alone carries the electrodes' state at
 * int 2 (0 while both are touched) and the group's number at int 3; every
 * other point has -1 there. A green PPG value rides at int 1 of the first
 * and the sixth point, the amplifier's thresholds at 4 and 5.
 */
internal class EcgPoint(val epochMillis: Long, val millivolts: Float, val leadOff: Int, val sequence: Int) {
    /** Whether this point says anything about the electrodes. */
    val hasLeadState: Boolean get() = leadOff != NOT_CARRIED

    companion object {
        const val NOT_CARRIED = -1
        const val LEAD_ON = 0
    }
}

internal fun RawPoint.toEcgPoint(): EcgPoint = EcgPoint(
    epochMillis,
    floats.getOrElse(0) { Float.NaN },
    ints.getOrElse(2) { EcgPoint.NOT_CARRIED },
    ints.getOrElse(3) { EcgPoint.NOT_CARRIED },
)

/**
 * One body composition point. The status at int 0 is 0 while the measurement
 * runs and at its result, or what stopped it (the platform's `BiaError`
 * ordinals); [progress] at float 9 counts up to 1, where the values are the
 * result. The ratios are per cent, the masses and the water kilograms, the
 * basal metabolic rate kilocalories a day.
 */
internal class BiaPoint(
    val epochMillis: Long,
    val status: Int,
    val bodyFatPercent: Float,
    val bodyFatMassKg: Float,
    val totalBodyWaterKg: Float,
    val skeletalMusclePercent: Float,
    val skeletalMuscleMassKg: Float,
    val basalMetabolicRateKcal: Float,
    val fatFreePercent: Float,
    val fatFreeMassKg: Float,
    val progress: Float,
) {
    val isResult: Boolean get() = status == STATUS_OK && progress >= 1f && bodyFatPercent > 0f && bodyFatPercent < 100f

    companion object {
        const val STATUS_OK = 0

        /**
         * "NV_NG": the platform's body composition library has no calibration (yet). The first point
         * of every run carries it: the library handles that point before the calibration the sensor
         * sends with it is set. A run whose points all carry it never got the calibration (the
         * platform drops it when its library, loaded on the first use in the process, is not ready
         * in time).
         */
        const val STATUS_NO_CALIBRATION = 2
        const val STATUS_WRIST_DETACHED = 4
        const val STATUS_FINGERS_DETACHED = 9
        const val STATUS_WRIST_LOOSE = 10
        const val STATUS_FINGERS_DRY = 11
        const val STATUS_HANDS_TOUCH = 14
        const val STATUS_FRAME_TOUCHED = 15
    }
}

internal fun RawPoint.toBiaPoint(): BiaPoint {
    fun value(index: Int) = floats.getOrElse(index) { Float.NaN }
    return BiaPoint(
        epochMillis = epochMillis,
        status = ints.getOrElse(0) { -1 },
        bodyFatPercent = value(1),
        bodyFatMassKg = value(2),
        totalBodyWaterKg = value(3),
        skeletalMusclePercent = value(4),
        skeletalMuscleMassKg = value(5),
        basalMetabolicRateKcal = value(6),
        fatFreePercent = value(7),
        fatFreeMassKg = value(8),
        progress = value(9),
    )
}

/**
 * One continuous PPG sample: per colour a value and its status, green at
 * ints 0-1, red at 2-3, infrared at 4-5. A colour that was not switched on
 * reads 0 with status -1.
 */
internal class PpgPoint(val epochMillis: Long, val green: Int, val greenStatus: Int, val red: Int, val infrared: Int)

internal fun RawPoint.toPpgPoint(): PpgPoint = PpgPoint(
    epochMillis,
    green = ints.getOrElse(0) { 0 },
    greenStatus = ints.getOrElse(1) { -1 },
    red = ints.getOrElse(2) { 0 },
    infrared = ints.getOrElse(4) { 0 },
)
