package com.brian.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brian.camera.camera.CameraEngine
import com.brian.camera.camera.Mode
import com.brian.camera.camera.Shooter
import com.brian.camera.ui.CameraScreen
import com.brian.camera.ui.CheckScreen
import com.brian.camera.ui.GalleryScreen
import com.brian.camera.ui.PermissionScreen
import com.brian.camera.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    lateinit var prefs: Prefs
    lateinit var engine: CameraEngine
    lateinit var shooter: Shooter

    private var screen by mutableStateOf("camera")
    private var granted by mutableStateOf(false)
    /** Set by the camera screen: what the volume keys should do. */
    var onVolume: ((up: Boolean) -> Unit)? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = has(Manifest.permission.CAMERA)
        if (granted) engine.start()
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else list += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= 28) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        askPerms.launch(list.toTypedArray())
    }

    val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) prefs.location = false
        engine.updateLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        engine = CameraEngine(this, this, prefs)
        shooter = Shooter(engine)
        when (intent?.action) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA -> engine.setMode(Mode.VIDEO)
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA -> engine.setMode(Mode.PHOTO)
        }

        granted = has(Manifest.permission.CAMERA)
        if (granted) engine.start() else requestPerms()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFFFC107), background = Color.Black, surface = Color(0xFF121212))) {
                if (!granted) {
                    PermissionScreen { requestPerms() }
                    return@MaterialTheme
                }
                BackHandler(enabled = screen != "camera") { screen = if (screen == "check") "settings" else "camera" }
                when (screen) {
                    "gallery" -> GalleryScreen(this, onBack = { screen = "camera" })
                    "settings" -> SettingsScreen(this, onBack = { screen = "camera" }, onCheck = { screen = "check" })
                    "check" -> CheckScreen(engine, onBack = { screen = "settings" })
                    else -> CameraScreen(this, onGallery = { screen = "gallery" }, onSettings = { screen = "settings" })
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (screen == "camera" && prefs.volumeKey != "Volume" && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event?.repeatCount == 0 || prefs.volumeKey == "Zoom") onVolume?.invoke(keyCode == KeyEvent.KEYCODE_VOLUME_UP)
            return true
        }
        if (screen == "camera" && keyCode == KeyEvent.KEYCODE_CAMERA) {
            onVolume?.invoke(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (screen == "camera" && prefs.volumeKey != "Volume" && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        engine.release()
        shooter.release()
        super.onDestroy()
    }
}
