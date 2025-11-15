package com.example.edgeviewer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.opengl.GLSurfaceView
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    // -----------------------------
    // UI
    // -----------------------------
    private lateinit var glView: GLSurfaceView
    private lateinit var glRenderer: GLRenderer
    private lateinit var fpsText: TextView
    private lateinit var toggleBtn: Button

    // -----------------------------
    // Camera2
    // -----------------------------
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private lateinit var imageReader: ImageReader

    // -----------------------------
    // Threads
    // -----------------------------
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null

    // -----------------------------
    // State
    // -----------------------------
    private var showEdges = true
    private var frameStartTime = 0L

    // -----------------------------
    // Activity Lifecycle
    // -----------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        glView = findViewById(R.id.glSurface)
        fpsText = findViewById(R.id.fpsText)
        toggleBtn = findViewById(R.id.toggleBtn)

        glRenderer = GLRenderer()
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(glRenderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        toggleBtn.setOnClickListener {
            showEdges = !showEdges
            toggleBtn.text = if (showEdges) "Show RAW" else "Show EDGES"
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()
        openCamera()
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    // ----------------------------------------------------
    // PERMISSIONS
    // ----------------------------------------------------
    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    // ----------------------------------------------------
    // Background Thread
    // ----------------------------------------------------
    private fun startBackgroundThread() {
        bgThread = HandlerThread("CameraBG").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)
    }

    private fun stopBackgroundThread() {
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
    }

    // ----------------------------------------------------
    // CAMERA OPEN
    // ----------------------------------------------------
    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList.first()
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)

            val streamConfigs = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )!!

            val width = 1280
            val height = 720

            imageReader = ImageReader.newInstance(
                width, height, ImageFormat.YUV_420_888, 3
            )
            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleImage(image)
            }, bgHandler)

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cd: CameraDevice) {
                    cameraDevice = cd
                    createSession()
                }

                override fun onDisconnected(cd: CameraDevice) {
                    cd.close()
                }

                override fun onError(cd: CameraDevice, error: Int) {
                    cd.close()
                }
            }, bgHandler)

        } catch (e: Exception) {
            Log.e("CAM", "Open camera failed", e)
        }
    }

    private fun closeCamera() {
        captureSession?.close()
        cameraDevice?.close()
    }

    // ----------------------------------------------------
    // Session
    // ----------------------------------------------------
    private fun createSession() {
        val surface = imageReader.surface
        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cs: CameraCaptureSession) {
                    captureSession = cs

                    val req = cameraDevice!!.createCaptureRequest(
                        CameraDevice.TEMPLATE_PREVIEW
                    )
                    req.addTarget(surface)

                    cs.setRepeatingRequest(req.build(), null, bgHandler)
                }

                override fun onConfigureFailed(cs: CameraCaptureSession) {}
            },
            bgHandler
        )
    }

    // ----------------------------------------------------
    // Frame Processing
    // ----------------------------------------------------
    private fun handleImage(image: Image) {
        frameStartTime = System.nanoTime()

        val width = image.width
        val height = image.height

        val nv21 = ByteArray(width * height * 3 / 2)
        yuv420ToNv21(image, nv21)
        image.close()

        val out = ByteBuffer.allocateDirect(width * height)

        if (showEdges) {
            NativeBridge.processFrame(nv21, width, height, out)
        } else {
            out.put(nv21, 0, width * height)
        }
        out.position(0)
        Log.e("SAVE", "out.remaining() = ${out.remaining()} / expected=${width * height}")


        // ---- Render to OpenGL ----
        out.position(0)
        glRenderer.updateFrame(out, width, height)
        glView.requestRender()

        // ---- FPS ----
        val dt = (System.nanoTime() - frameStartTime) / 1_000_000
        runOnUiThread {
            fpsText.text = "${dt}ms"
        }
    }

    // ----------------------------------------------------
    // FIXED — SAFE NV21 CONVERTER
    // ----------------------------------------------------
    private fun yuv420ToNv21(image: Image, nv21: ByteArray) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var outPos = 0

        // Copy Y
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
            outPos = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, outPos, width)
                outPos += width
            }
        }

        // Copy interleaved VU
        val chromaHeight = height / 2
        val chromaWidth = width / 2

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[outPos++] = vBuffer[uvIndex]
                nv21[outPos++] = uBuffer[uvIndex]
            }
        }
    }

    // ----------------------------------------------------
    // OPTIONAL IMAGE SAVING (Disabled)
    // ----------------------------------------------------
    private fun grayscaleBytesToBitmap(bytes: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun saveBitmapToInternalPng(bitmap: Bitmap, filename: String): File {
        val file = File(filesDir, filename)
        FileOutputStream(file).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return file
    }

    private fun saveBase64ToInternalFile(base64: String, filename: String): File {
        val file = File(filesDir, filename)
        file.writeText(base64)
        return file
    }
}
