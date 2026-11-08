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

/**
 * The heart rate tracker of the Samsung Health Platform
 * (`com.samsung.android.service.health`), spoken to over Binder with no
 * vendor library. Written from the protocol observed on a Galaxy Watch7;
 * `docs/engineering/samsung-health-and-privacy.md` records it, and why the
 * Samsung Health Sensor SDK itself never enters this repository.
 *
 * Outbound, on the service token: 1 create(appId, type, profile?, exercise?),
 * 2 setListener(appId, type, binder), 3 unsetListener(appId, type),
 * 4 flush(appId, type). Inbound, on the listener token: 1 data (a typed list
 * of points), 2 error(int), 3 flush done. A point is a time and a generic
 * list of values, each an (int, float, long); for the heart rate, value 3 is
 * the interval count and the (interval, status) pairs follow from value 4.
 *
 * The platform keys its clients by package, so this app holds one tracker at
 * a time. Outside the platform's Developer Mode the platform asks Samsung
 * whether this package may use it before it sends data; the app cannot read
 * the mode, so this source is opt-in and the setting says so.
 *
 * Every callback is posted to [handler].
 */
internal class SamsungHealthPlatform(
    private val context: Context,
    private val handler: Handler,
    /** One delivery: the points of one platform callback, oldest first as sent. */
    private val onPoints: (List<IbiPoint>) -> Unit,
    private val onFlushed: () -> Unit,
    private val onError: () -> Unit,
) {
    private var service: IBinder? = null
    private var bound = false

    private val listener = object : Binder() {
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
                        handler.post { onPoints(points) }
                    }
                    CB_ERROR -> {
                        Log.w(TAG, "Platform error ${data.readInt()}")
                        handler.post(onError)
                    }
                    else -> handler.post(onFlushed)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Unreadable callback", e)
                handler.post(onError)
            }
            reply?.writeNoException()
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = binder
            try {
                call(TX_CREATE) { writeString(appId()); writeString(TRACKER_HEART_RATE); writeInt(0); writeString(null) }
                call(TX_SET_LISTENER) { writeString(appId()); writeString(TRACKER_HEART_RATE); writeStrongBinder(listener) }
            } catch (e: RemoteException) {
                Log.w(TAG, "Start refused", e)
                onError()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Start refused", e)
                onError()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            onError()
        }
    }

    /** Binds and starts the tracker. False when the platform is not there or refused the bind. */
    fun start(): Boolean {
        bound = try {
            context.bindService(bindIntent(), Context.BIND_AUTO_CREATE, Executor { handler.post(it) }, connection)
        } catch (_: SecurityException) {
            false
        }
        if (!bound) Log.w(TAG, "Bind refused")
        return bound
    }

    /** Asks for the points the platform still holds; [onFlushed] follows them. False when not connected. */
    fun flush(): Boolean = try {
        service != null && run { call(TX_FLUSH) { writeString(appId()); writeString(TRACKER_HEART_RATE) }; true }
    } catch (_: RemoteException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun stop() {
        try {
            if (service != null) call(TX_UNSET_LISTENER) { writeString(appId()); writeString(TRACKER_HEART_RATE) }
        } catch (_: RemoteException) {
            // The platform is gone; there is nothing left to unset.
        } catch (_: RuntimeException) {
            // As above.
        }
        if (bound) context.unbindService(connection)
        bound = false
        service = null
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
    private fun readPoints(p: Parcel): List<IbiPoint> {
        val points = ArrayList<IbiPoint>()
        repeat(p.readInt()) {
            if (p.readInt() == 0) return@repeat
            val timestamp = p.readLong()
            val count = p.readInt().coerceAtLeast(0)
            val ints = IntArray(count)
            for (v in 0 until count) {
                val type = p.readInt()
                check(type == VAL_PARCELABLE) { "value type $type" }
                val afterValue = readOptionalLengthPrefix(p)
                p.readString() // the value's class name
                ints[v] = p.readInt()
                p.readFloat()
                p.readLong()
                if (afterValue >= 0) p.setDataPosition(afterValue)
            }
            points += heartRatePoint(timestamp, ints)
        }
        return points
    }

    private fun heartRatePoint(timestamp: Long, values: IntArray): IbiPoint {
        val pairs = if (values.size > IBI_COUNT) minOf(values[IBI_COUNT], (values.size - IBI_FIRST) / 2).coerceAtLeast(0) else 0
        return IbiPoint(
            epochMillis = timestamp,
            intervalsMillis = IntArray(pairs) { values[IBI_FIRST + 2 * it] },
            statuses = IntArray(pairs) { values[IBI_FIRST + 2 * it + 1] },
            bpm = values.getOrElse(HEART_RATE) { 0 },
            heartRateStatus = values.getOrElse(HEART_RATE_STATUS) { 0 },
        )
    }

    companion object {
        private const val TAG = "SamsungHealthPlatform"
        private const val SERVICE_PACKAGE = "com.samsung.android.service.health"
        private const val BIND_ACTION = "com.samsung.android.service.health.wear.sts.ACTION_SERVICE"
        private const val SERVICE_TOKEN = "com.samsung.android.service.health.tracking.IClientEventListener"
        private const val LISTENER_TOKEN = "com.samsung.android.service.health.tracking.IServiceEventListener"
        private const val TRACKER_HEART_RATE = "HEART_RATE"
        private const val TX_CREATE = 1
        private const val TX_SET_LISTENER = 2
        private const val TX_UNSET_LISTENER = 3
        private const val TX_FLUSH = 4
        private const val CB_DATA = 1
        private const val CB_ERROR = 2
        private const val CB_FLUSH_DONE = 3
        private const val VAL_PARCELABLE = 4
        private const val HEART_RATE = 0
        private const val HEART_RATE_STATUS = 2
        private const val IBI_COUNT = 3
        private const val IBI_FIRST = 4

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
