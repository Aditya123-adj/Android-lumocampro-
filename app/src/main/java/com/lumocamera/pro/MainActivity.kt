package com.lumocamera.pro

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private lateinit var executor: ExecutorService
    private var still: ImageCapture? = null
    private var video: VideoCapture<Recorder>? = null
    private var camera: Camera? = null
    private var recording: androidx.camera.video.Recording? = null
    private var videoMode = false
    private var activeRecording = false
    private var selector = CameraSelector.DEFAULT_BACK_CAMERA
    private var zoomRatio = 1f
    private lateinit var modeInfo: TextView
    private lateinit var shutter: TextView

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) startCamera()
        else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        executor = Executors.newSingleThreadExecutor()
        createUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun label(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun createUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(22, 12, 22, 12)
            background = GlassBackground(0x9930303A.toInt(), 28f)
        }
        top.addView(label("LUMO", 18f, Color.WHITE, true))
        val spacer = View(this); top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(label("PRO  •  AUTO", 11f, 0xFFD9C8FF.toInt(), true))
        root.addView(top, FrameLayout.LayoutParams(-1, 56, Gravity.TOP).apply { setMargins(14, 18, 14, 0) })

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(14, 16, 14, 18)
            background = GlassBackground(0xDD171720.toInt(), 30f)
        }
        val zooms = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf(0.6f, 1f, 2f, 3f).forEach { factor ->
            val t = label(if (factor == 1f) "1×" else factor.toString() + "×", 14f, Color.WHITE, true)
            t.setPadding(15, 10, 15, 10); t.setOnClickListener { setZoom(factor) }; zooms.addView(t)
        }
        panel.addView(zooms)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 12, 0, 6) }
        val gallery = label("▧", 25f, Color.WHITE)
        gallery.setOnClickListener { runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")) } }
        shutter = label("●", 60f, Color.WHITE, true)
        shutter.setOnClickListener { if (videoMode) toggleVideo() else takePhoto() }
        val flip = label("↻", 28f, Color.WHITE)
        flip.setOnClickListener { selector = if (selector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA; startCamera() }
        controls.addView(gallery, LinearLayout.LayoutParams(0, 70, 1f))
        controls.addView(shutter, LinearLayout.LayoutParams(0, 70, 1.3f))
        controls.addView(flip, LinearLayout.LayoutParams(0, 70, 1f))
        panel.addView(controls)
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val photo = label("PHOTO", 12f, Color.WHITE, true).apply { setPadding(24, 10, 24, 10); setOnClickListener { videoMode = false; updateMode() } }
        val vid = label("VIDEO", 12f, 0xFFD9C8FF.toInt(), true).apply { setPadding(24, 10, 24, 10); setOnClickListener { videoMode = true; updateMode() } }
        modes.addView(photo); modes.addView(vid); panel.addView(modes)
        modeInfo = label("Photo mode", 10f, 0xFFB9B9C5.toInt()); panel.addView(modeInfo)
        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(12, 0, 12, 12) })
        setContentView(root)
        updateMode()
    }

    private fun updateMode() {
        if (!::modeInfo.isInitialized) return
        modeInfo.text = if (activeRecording) "Recording…" else if (videoMode) "Video mode • tap shutter to record" else "Photo mode"
        shutter.text = if (activeRecording) "■" else "●"
        shutter.setTextColor(if (activeRecording) 0xFFFF7777.toInt() else Color.WHITE)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.FHD)).build()
            video = VideoCapture.withOutput(recorder)
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, previewUseCase, still, video)
                setZoom(zoomRatio)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera could not start: " + (e.localizedMessage ?: "unsupported mode"), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setZoom(value: Float) {
        zoomRatio = value
        val state = camera?.cameraInfo?.zoomState?.value ?: return
        camera?.cameraControl?.setZoomRatio(value.coerceIn(state.minZoomRatio, state.maxZoomRatio))
    }

    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

    private fun takePhoto() {
        val capture = still ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LUMO_" + stamp() + ".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LumoCamera")
        }
        val options = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) { runOnUiThread { Toast.makeText(this@MainActivity, "Photo saved", Toast.LENGTH_SHORT).show() } }
            override fun onError(error: ImageCaptureException) { runOnUiThread { Toast.makeText(this@MainActivity, error.message ?: "Photo failed", Toast.LENGTH_SHORT).show() } }
        })
    }

    private fun toggleVideo() {
        if (activeRecording) { recording?.stop(); return }
        val capture = video ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LUMO_" + stamp() + ".mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LumoCamera")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build()
        var pending = capture.output.prepareRecording(this, output)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> { activeRecording = true; updateMode() }
                is VideoRecordEvent.Finalize -> {
                    activeRecording = false; recording = null; updateMode()
                    Toast.makeText(this, if (event.hasError()) "Video recording failed" else "Video saved", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() { recording?.stop(); executor.shutdown(); super.onDestroy() }
}