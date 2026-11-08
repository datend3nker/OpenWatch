package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * The debug build's measurement log: what the watch did and what it cost, so
 * a worn night answers the open power and source questions without adb
 * attached. Debuggable builds only; a release build never writes it.
 *
 * Two kinds of line, one JSON object each, in `files/debug/protocol-<day>.jsonl`:
 * - an event (`"k":"ev"`): a recorder changed its plan, a heart rate window
 *   opened or closed, a Samsung platform delivery arrived, an HRV window ended;
 * - a snapshot (`"k":"snap"`): the battery, the power state and the plan in
 *   force, plus every counter since the last snapshot, then the counters
 *   reset. [DebugSnapshots] writes one at each battery percent and at each
 *   screen, idle, charger or plan change, so the log never wakes the watch
 *   itself.
 *
 * The file stays in the app's private storage; `scripts/wear-debug-report.py`
 * pulls it with `run-as`. Seven days are kept.
 */
object DebugProtocol {

    @Volatile
    var isEnabled: Boolean = false
        private set

    private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "DebugProtocol") }
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val lastArrival = ConcurrentHashMap<String, Long>()
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun init(context: Context) {
        if (isEnabled) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        dir = File(context.filesDir, "debug").apply { mkdirs() }
        isEnabled = true
        writer.execute { pruneOldFiles() }
    }

    /** Adds [n] to the counter [key]; the next snapshot reports and resets it. */
    fun count(key: String, n: Long = 1) {
        if (!isEnabled) return
        counters.getOrPut(key) { AtomicLong() }.addAndGet(n)
    }

    /**
     * One sensor event for [key]: counts it under `<key>.events`, and under
     * `<key>.batches` when it is the first after a pause, which is one
     * delivery and, for a batched sensor, one wake-up of the processor.
     */
    fun arrival(key: String) {
        if (!isEnabled) return
        count("$key.events")
        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastArrival.put(key, now)
        if (last == null || now - last > BATCH_GAP_MILLIS) count("$key.batches")
    }

    /** Keeps the largest value of [key] since the last snapshot. */
    fun max(key: String, value: Long) {
        if (!isEnabled) return
        val counter = counters.getOrPut(key) { AtomicLong(Long.MIN_VALUE) }
        while (true) {
            val current = counter.get()
            if (value <= current || counter.compareAndSet(current, value)) return
        }
    }

    fun event(source: String, name: String, vararg fields: Pair<String, Any?>) {
        if (!isEnabled) return
        val line = JSONObject().put("t", System.currentTimeMillis()).put("k", "ev").put("src", source).put("ev", name)
        for ((key, value) in fields) line.put(key, value ?: JSONObject.NULL)
        append(line)
    }

    /** A snapshot line from [fields] and the counters, which then start again from zero. */
    fun snapshot(reason: String, fields: Map<String, Any?>) {
        if (!isEnabled) return
        val line = JSONObject().put("t", System.currentTimeMillis()).put("k", "snap").put("why", reason)
        for ((key, value) in fields) line.put(key, value ?: JSONObject.NULL)
        val drained = JSONObject()
        for ((key, counter) in counters) {
            val value = counter.getAndSet(if (key.endsWith(".max")) Long.MIN_VALUE else 0L)
            if (value != 0L && value != Long.MIN_VALUE) drained.put(key, value)
        }
        line.put("c", drained)
        append(line)
    }

    private fun append(line: JSONObject) {
        val target = dir ?: return
        writer.execute {
            try {
                File(target, "protocol-${day.format(Date())}.jsonl").appendText(line.toString() + "\n")
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Protocol write failed", e)
            }
        }
    }

    private fun pruneOldFiles() {
        val files = dir?.listFiles { file -> file.name.startsWith("protocol-") }?.sortedBy { it.name } ?: return
        files.dropLast(KEEP_DAYS).forEach { it.delete() }
    }

    private const val TAG = "DebugProtocol"
    private const val KEEP_DAYS = 7

    /** Events closer than this belong to one delivery. */
    private const val BATCH_GAP_MILLIS = 2_000L
}
