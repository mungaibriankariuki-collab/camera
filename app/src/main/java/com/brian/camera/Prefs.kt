package com.brian.camera

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All user settings. Each one is Compose state backed by SharedPreferences. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = Pref(key, def, { sp.getBoolean(key, def) }, { sp.edit().putBoolean(key, it).apply() })
    private fun int(key: String, def: Int) = Pref(key, def, { sp.getInt(key, def) }, { sp.edit().putInt(key, it).apply() })
    private fun str(key: String, def: String) = Pref(key, def, { sp.getString(key, def) ?: def }, { sp.edit().putString(key, it).apply() })

    // Shooting
    var lastMode by str("lastMode", "PHOTO")
    var keepLastMode by bool("keepLastMode", true)
    var flash by int("flash", 0)              // 0 off, 1 auto, 2 on
    var timer by int("timer", 0)              // seconds
    var ratio by str("ratio", "3:4")          // 3:4, 9:16, 1:1
    var highRes by bool("highRes", false)     // 50 MP photo
    var filter by str("filter", "None")
    var portraitEffect by str("portraitEffect", "Blur")
    var portraitStrength by int("portraitStrength", 6)
    var nightFrames by int("nightFrames", 6)
    var hyperlapseSpeed by int("hyperlapseSpeed", 8)
    var lensTool by str("lensTool", "Text")
    var translateTo by str("translateTo", "en")
    var frontCamera by bool("frontCamera", false)
    var autoFraming by bool("autoFraming", false)

    // Intelligent features
    var sceneOptimizer by bool("sceneOptimizer", true)
    var shotSuggestions by bool("shotSuggestions", true)
    var scanQr by bool("scanQr", true)

    // Pictures
    var processing by str("processing", "Detailed") // Detailed (multi-frame) or Fast
    var heif by bool("heif", false)
    var watermark by bool("watermark", false)
    var watermarkText by str("watermarkText", "Shot on M-KOPA X30")
    var mirrorSelfie by bool("mirrorSelfie", true)
    var location by bool("location", false)
    var stripLocationOnShare by bool("stripLocationOnShare", true)

    // Videos
    var videoQuality by str("videoQuality", "FHD")
    var stabilization by bool("stabilization", true)
    var recordAudio by bool("recordAudio", true)

    // Useful features
    var grid by int("grid", 0)                // 0 off, 3, 4
    var level by bool("level", true)
    var shutterSound by bool("shutterSound", true)
    var volumeKey by str("volumeKey", "Shutter") // Shutter, Zoom, Volume
    var palmGesture by bool("palmGesture", true)
    var voiceControl by bool("voiceControl", false)
    var swipeToSwitch by bool("swipeToSwitch", true)

    /** My Filters: name -> 20-float ColorMatrix, stored as JSON. */
    var myFiltersJson by str("myFilters", "[]")

    fun myFilters(): List<Pair<String, FloatArray>> = runCatching {
        val arr = JSONArray(myFiltersJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val m = o.getJSONArray("m")
            o.getString("name") to FloatArray(20) { m.getDouble(it).toFloat() }
        }
    }.getOrDefault(emptyList())

    fun saveMyFilters(list: List<Pair<String, FloatArray>>) {
        val arr = JSONArray()
        list.forEach { (n, m) -> arr.put(JSONObject().put("name", n).put("m", JSONArray(m.map { it.toDouble() }))) }
        myFiltersJson = arr.toString()
    }

    private class Pref<T>(val key: String, def: T, read: () -> T, val write: (T) -> Unit) : ReadWriteProperty<Any, T> {
        private var state = mutableStateOf(read())
        override fun getValue(thisRef: Any, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any, property: KProperty<*>, value: T) {
            state.value = value
            write(value)
        }
    }
}
