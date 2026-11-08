package tech.mmarca.openwatch.wear.samsung

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.util.Log
import java.util.concurrent.Executor

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
 * 4 flush(appId, type). Inbound, on the listener token: 1 data (a typed list
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
    /** What one tracker type delivers to; [onError] is that tracker's own failure (a refused permission, a platform error). */
    class Tracker(val onPoints: (List<RawPoint>) -> Unit, val onFlushed: () -> Unit, val onError: () -> Unit)

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
            call(TX_CREATE) { writeString(appId()); writeString(type); writeInt(0); writeString(null) }
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

    /** A typed list: a count, then per item a presence flag and the point. */
    private fun readPoints(p: Parcel): List<RawPoint> {
        val points = ArrayList<RawPoint>()
        repeat(p.readInt()) {
            if (p.readInt() == 0) return@repeat
            val timestamp = p.readLong()
            val count = p.readInt().coerceAtLeast(0)
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
                if (afterValue >= 0) p.setDataPosition(afterValue)
            }
            points += RawPoint(timestamp, ints, floats)
        }
        return points
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
        private const val TX_CREATE = 1
        private const val TX_SET_LISTENER = 2
        private const val TX_UNSET_LISTENER = 3
        private const val TX_FLUSH = 4
        private const val CB_DATA = 1
        private const val CB_ERROR = 2
        private const val CB_FLUSH_DONE = 3
        private const val VAL_PARCELABLE = 4

        private fun bindIntent() = Intent(BIND_ACTION).setPackage(SERVICE_PACKAGE)

        /** Whether this watch has the platform's tracking service: Samsung watches only. */
        fun isInstalled(context: Context): Boolean =
            context.packageManager.queryIntentServices(bindIntent(), 0).isNotEmpty()

        /**
         * Newer platforms write a length before a parcelable inside a generic
         * list; older ones do not. Peek: if the next int is a plausible length
         * followed by a class name, consume it and return where the value
         * ends; otherwise rewind and return -1.
         */
        private fun readOptionalLengthPrefix(p: Parcel): Int {
            val start = p.dataPosition()
            val length = p.readInt()
            val afterLength = p.dataPosition()
            if (length > 0 && afterLength + length <= p.dataSize()) {
                val name = p.readString()
                if (name != null && name.indexOf('.') > 0) {
                    p.setDataPosition(afterLength)
                    return afterLength + length
                }
            }
            p.setDataPosition(start)
            return -1
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

/** A skin temperature point: the object temperature at float 0, the ambient at float 1, the status at int 2. */
internal class SkinPoint(val epochMillis: Long, val objectCelsius: Float, val ambientCelsius: Float, val status: Int)

internal fun RawPoint.toSkinPoint(): SkinPoint =
    SkinPoint(epochMillis, floats.getOrElse(0) { Float.NaN }, floats.getOrElse(1) { Float.NaN }, ints.getOrElse(2) { 0 })

/**
 * An SpO2 point: the status at int 0 (2 when the measurement completed, -4
 * when the watch moved, -6 after the measurement ended, 0 while it runs, as
 * seen on a Galaxy Watch7), the percentage at 1, the heart rate at 2.
 */
internal class Spo2Point(val epochMillis: Long, val status: Int, val percent: Int, val bpm: Int) {
    val isComplete: Boolean get() = status == STATUS_COMPLETED && percent in 70..100

    companion object {
        const val STATUS_COMPLETED = 2
        const val STATUS_MOVING = -4
    }
}

internal fun RawPoint.toSpo2Point(): Spo2Point =
    Spo2Point(epochMillis, ints.getOrElse(0) { 0 }, ints.getOrElse(1) { 0 }, ints.getOrElse(2) { 0 })
