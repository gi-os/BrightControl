package com.gios.lightcontrol.report

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * The accelerometer, on only while you are looking at the app.
 *
 * Registered from `onResume` and dropped in `onPause`, which is what keeps this from being a
 * battery question at all: a 50Hz stream costs real power, and there is no case where shaking a
 * phone that is showing something else should file a report against this app.
 *
 * The decision of what counts as a shake is in [ShakeGesture] and is plain arithmetic; this
 * class only turns three floats into a magnitude and hands it over.
 */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gesture = ShakeGesture()

    /** False on a phone with no accelerometer, where the whole feature quietly does not exist. */
    val available: Boolean get() = accelerometer != null

    fun start() {
        val sensor = accelerometer ?: return
        gesture.reset()
        // GAME is 50Hz. NORMAL is 5Hz, which is slower than the gesture it has to see.
        sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensors?.unregisterListener(this)
        gesture.reset()
    }

    /** Called when the sheet opens, so the shake that opened it cannot open a second one. */
    fun forget() = gesture.reset()

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        // The event's own timestamp is nanoseconds since boot from the sensor hub, which is
        // the right clock here: it does not drift with the main thread being busy, and being
        // busy is exactly the state a freeze report is filed in.
        if (gesture.sample(event.timestamp / 1_000_000L, magnitude)) onShake()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
