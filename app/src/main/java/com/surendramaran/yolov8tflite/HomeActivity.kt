package com.surendramaran.yolov8tflite

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import yolov8tflite.R
import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.*

class HomeActivity : AppCompatActivity() {

    private lateinit var move: Button
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var recognizerIntent: Intent
    private val RECORD_REQUEST_CODE = 101
    private var isActivityActive = true

    private var currentToast: Toast? = null

    private lateinit var textToSpeech: TextToSpeech

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_home)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
            }
        })

        move = findViewById(R.id.button_start)
        move.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }

        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Set the language
                val result = textToSpeech.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Language not supported or missing data")
                }
            } else {
                Log.e("TTS", "Initialization failed")
            }
        }

        requestPermissions()
    }

    private fun speak(text: String) {
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
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
                showToast("Permission Denied")
            }
        }
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, this@HomeActivity.packageName)
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
                    cancelCurrentToast()
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.let {
                        val recognizedText = it[0]
                    }
                    if (isActivityActive) {
                        speechRecognizer.startListening(recognizerIntent)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    cancelCurrentToast()
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.let {
                        val partialText = it[0]
                        handlePartialSpeech(partialText)
                    }
                }
            })

            startSpeechToText()

        } catch (e: Exception) {
            showToast("Error initializing SpeechRecognizer: ${e.message}")
        }
    }

    private fun handlePartialSpeech(partialText: String) {
        when {
            partialText.contains("start", ignoreCase = true) -> {
                speak("Start!")

                Handler(Looper.getMainLooper()).postDelayed({
                    move.performClick()
                }, 500)
            }
            partialText.contains("hello", ignoreCase = true) -> speak("Hi!")
            partialText.contains("hi", ignoreCase = true) -> speak("Hello!")
            partialText.contains("hep hep", ignoreCase = true) -> speak("Hooray!")
            partialText.contains("hooray", ignoreCase = true) -> speak("Hephep!")
            partialText.contains("ready", ignoreCase = true) -> speak("Ready!")
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
            showToast("Error starting recognizer: ${e.message}")
        }
    }

    private fun showToast(message: String) {
        cancelCurrentToast()

        currentToast = Toast.makeText(this, message, Toast.LENGTH_SHORT).apply {
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, 75)
            show()
        }
    }

    private fun cancelCurrentToast() {
        currentToast?.cancel()
        currentToast = null
    }

    override fun onPause() {
        super.onPause()
        isActivityActive = false
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.stopListening()
            speechRecognizer.cancel()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityActive = true
        if (this::speechRecognizer.isInitialized) {
            startSpeechToText()
        }
    }

    override fun onDestroy() {
        if (this::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}
