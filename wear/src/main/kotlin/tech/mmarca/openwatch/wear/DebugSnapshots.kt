package tech.mmarca.openwatch.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.PowerManager

/**
 * Writes a [DebugProtocol] snapshot whenever the battery level moves by a
 * percent and whenever the screen, Doze, battery saver or the charger
 * change, so every stretch between two lines has one state and a known
 * cost. It listens to broadcasts the system sends anyway and wakes nothing.
 *
 * [plan] adds what the app runs at that moment: the heart rate interval and
 * source, the HRV source, which recorders run, bedtime and worn state.
 */
class DebugSnapshots(
    private val context: Context,
    private val handler: Handler,
    private val plan: () -> Map<String, Any?>,
) {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private var lastLevel = -1

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    if (level == lastLevel) return
                    lastLevel = level
                    write("level")
                }
                Intent.ACTION_SCREEN_ON -> write("screen_on")
                Intent.ACTION_SCREEN_OFF -> write("screen_off")
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> write("idle")
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> write("power_save")
                Intent.ACTION_POWER_CONNECTED -> write("plugged")
                Intent.ACTION_POWER_DISCONNECTED -> write("unplugged")
            }
        }
    }

    fun start() {
        if (!DebugProtocol.isEnabled) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // System broadcasts only; none of them needs the exported flag.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter, null, handler)
        }
        write("start")
    }

    fun stop() {
        if (!DebugProtocol.isEnabled) return
        runCatching { context.unregisterReceiver(receiver) }
        write("stop")
    }

    /** A snapshot now, for a change the app itself made (a new plan). */
    fun write(reason: String) {
        if (!DebugProtocol.isEnabled) return
        val fields = LinkedHashMap<String, Any?>()
        fields["level"] = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        fields["chargeUah"] = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        fields["currentUa"] = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        fields["charging"] = battery.isCharging
        fields["screen"] = power.isInteractive
        fields["idle"] = power.isDeviceIdleMode
        fields["lightIdle"] = if (android.os.Build.VERSION.SDK_INT >= 33) power.isDeviceLightIdleMode else null
        fields["saver"] = power.isPowerSaveMode
        fields.putAll(plan())
        DebugProtocol.snapshot(reason, fields)
    }
}
