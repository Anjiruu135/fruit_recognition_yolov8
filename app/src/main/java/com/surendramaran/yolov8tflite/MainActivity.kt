package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import yolov8tflite.R
import yolov8tflite.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.Surface
import java.util.Locale
import android.speech.RecognitionListener
import android.speech.RecognizerIntent

class MainActivity : AppCompatActivity(), Detector.DetectorListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService

    private lateinit var textToSpeech: TextToSpeech

    private val totalDetectedLabels = mutableMapOf<String, Int>()
    private val detectedLabels = mutableMapOf<String, Int>()

    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private val RECORD_REQUEST_CODE = 101
    private var isActivityActive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {
            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this) {
                toast(it)
            }
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
 
        bindListeners()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val intent = Intent(this@MainActivity, HomeActivity::class.java)
                startActivity(intent)
            }
        })

        val toggleButton: ToggleButton = findViewById(R.id.isGpu)

        toggleButton.visibility = View.GONE

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported or missing data")
                }
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }

        binding.root.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                displayDetectedLabels()
                v.performClick()
                return@setOnTouchListener true
            }
            false
        }

        setupFruitButtonActions()
        requestPermissions()
    }

    private fun requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_REQUEST_CODE
            )
        } else {
            setupSpeechRecognizer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == RECORD_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupSpeechRecognizer()
            } else {

            }
        }
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.orange))
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
        }
    }

    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val display = binding.viewFinder.display
        val rotation = display?.rotation ?: Surface.ROTATION_0

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(
                imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
            )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
            speechRecognizer.cancel()
        }
    }

    override fun onDestroy() {
        if (this::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
        detector?.close()
        cameraExecutor.shutdown()
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        isActivityActive = true
        if (this::speechRecognizer.isInitialized) {
            startSpeechToText()
        }
    }

    private var lastEmptyDetectTime: Long = 0
    private val emptyDetectThrottle = 5000L

    override fun onEmptyDetect() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastEmptyDetectTime > emptyDetectThrottle) {
            lastEmptyDetectTime = currentTime
            runOnUiThread {
                binding.overlay.clear()
                detectedLabels.clear()
            }
        }
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }

            detectedLabels.clear()

            for (box in boundingBoxes) {
                val label = box.clsName
                detectedLabels[label] = detectedLabels.getOrDefault(label, 0) + 1
            }
        }
    }

    private fun displayDetectedLabels() {
        if (detectedLabels.isEmpty()) {
            val message = "No fruits detected"
            speak(message)
        } else {
            var appleCount = 0
            var bananaCount = 0
            var mangoCount = 0
            var orangeCount = 0
            var tomatoCount = 0

            for ((label, count) in detectedLabels) {
                totalDetectedLabels[label] = totalDetectedLabels.getOrDefault(label, 0) + count
            }

            for ((label, count) in totalDetectedLabels) {
                when {
                    label.startsWith("apple") -> appleCount += count
                    label.startsWith("banana") -> bananaCount += count
                    label.startsWith("mango") -> mangoCount += count
                    label.startsWith("orange") -> orangeCount += count
                    label.startsWith("tomato") -> tomatoCount += count
                }
            }

            val appleButton = findViewById<Button>(R.id.apple_button)
            appleButton.text = "$appleCount"
            val bananaButton = findViewById<Button>(R.id.banana_button)
            bananaButton.text = "$bananaCount"
            val mangoButton = findViewById<Button>(R.id.mango_button)
            mangoButton.text = "$mangoCount"
            val orangeButton = findViewById<Button>(R.id.orange_button)
            orangeButton.text = "$orangeCount"
            val tomatoButton = findViewById<Button>(R.id.tomato_button)
            tomatoButton.text = "$tomatoCount"

            val currentMessage = detectedLabels.entries.joinToString(separator = "\n") { "${it.key}: ${it.value}" }
            val totalMessage = totalDetectedLabels.entries.joinToString(separator = "\n") { "${it.key}: ${it.value}" }
            val fullMessage = "Captured fruits:\n$currentMessage\n\nTotal fruits:\n$totalMessage"
            speak(fullMessage)
        }
    }

    private var currentToast: Toast? = null

    private fun toast(message: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
        currentToast?.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 75)
        currentToast?.show()
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this@MainActivity.packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onEndOfSpeech() {
                    if (isActivityActive) {
                        speechRecognizer.stopListening()
                        speechRecognizer.startListening(recognizerIntent)
                    }
                }

                override fun onError(error: Int) {
                    if (isActivityActive) {
                        speechRecognizer.startListening(recognizerIntent)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.let {
                        val recognizedText = it[0]
                    }
                    if (isActivityActive) {
                        speechRecognizer.startListening(recognizerIntent)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.let {
                        val partialText = it[0]
                        handlePartialSpeech(partialText)
                    }
                }
            })

            startSpeechToText()

        } catch (e: Exception) {

        }
    }

    private fun handlePartialSpeech(partialText: String) {
        when {
            partialText.contains("capture", ignoreCase = true) -> {
                displayDetectedLabels()
            }
            partialText.contains("hello", ignoreCase = true) -> speak("Hi!")
            partialText.contains("hi", ignoreCase = true) -> speak("Hello!")
            partialText.contains("hep hep", ignoreCase = true) -> speak("Hooray!")
            partialText.contains("hooray", ignoreCase = true) -> speak("Hephep!")
            partialText.contains("ready", ignoreCase = true) -> speak("Ready!")
            partialText.contains("apple", ignoreCase = true) -> {
                countAndSpeakFruitConditions("apple")
            }
            partialText.contains("banana", ignoreCase = true) -> {
                countAndSpeakFruitConditions("banana")
            }
            partialText.contains("mango", ignoreCase = true) -> {
                countAndSpeakFruitConditions("mango")
            }
            partialText.contains("orange", ignoreCase = true) -> {
                countAndSpeakFruitConditions("orange")
            }
            partialText.contains("tomato", ignoreCase = true) -> {
                countAndSpeakFruitConditions("tomato")
            }
        }
        speechRecognizer.stopListening()
        if (isActivityActive) {
            speechRecognizer.startListening(recognizerIntent)
        }
    }

    private fun startSpeechToText() {
        try {
            speechRecognizer.startListening(recognizerIntent)
        } catch (e: Exception) {
        }
    }

    private fun countAndSpeakFruit(fruit: String, count: Int) {
        val message = if (count > 0) {
            "$fruit count: $count"
        } else {
            "No $fruit detected"
        }
        speak(message)
    }

    private fun countAndSpeakFruitConditions(fruit: String) {
        val ripeCount = totalDetectedLabels.filterKeys { it == "$fruit-ripe" }.values.sum()
        val unripeCount = totalDetectedLabels.filterKeys { it == "$fruit-unripe" }.values.sum()
        val rottenCount = totalDetectedLabels.filterKeys { it == "$fruit-rotten" }.values.sum()
        val defectCount = totalDetectedLabels.filterKeys { it == "$fruit-defect" }.values.sum()

        if (ripeCount == 0 && unripeCount == 0 && rottenCount == 0 && defectCount == 0) {
            speak("No $fruit detected")
        } else {
            val message = """
            $fruit status:
            Ripe: $ripeCount
            Unripe: $unripeCount
            Rotten: $rottenCount
            Defect: $defectCount
        """.trimIndent()

            speak(message)
        }
    }

    private fun setupFruitButtonActions() {
        val appleButton = findViewById<Button>(R.id.apple_button)
        val bananaButton = findViewById<Button>(R.id.banana_button)
        val mangoButton = findViewById<Button>(R.id.mango_button)
        val orangeButton = findViewById<Button>(R.id.orange_button)
        val tomatoButton = findViewById<Button>(R.id.tomato_button)

        appleButton.setOnClickListener {
            countAndSpeakFruitConditions("apple")
        }

        bananaButton.setOnClickListener {
            countAndSpeakFruitConditions("banana")
        }

        mangoButton.setOnClickListener {
            countAndSpeakFruitConditions("mango")
        }

        orangeButton.setOnClickListener {
            countAndSpeakFruitConditions("orange")
        }

        tomatoButton.setOnClickListener {
            countAndSpeakFruitConditions("tomato")
        }
    }
}
