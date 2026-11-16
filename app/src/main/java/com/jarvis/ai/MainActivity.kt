
package com.jarvis.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import kotlin.math.*

enum class JarvisState {
    LISTENING, THINKING, SPEAKING, WAITING
}

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null

    private var currentState by mutableStateOf(JarvisState.WAITING)
    private var lastMessage by mutableStateOf("SYSTEM READY")

    private val serverUrl =
        "https://jarvis-core-server.astrogame373.workers.dev"

    private val restartListening = Runnable {
        if (!isFinishing && currentState != JarvisState.SPEAKING) {
            startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JarvisApp(
                state = currentState,
                message = lastMessage
            )
        }

        val permissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    initializeListening()
                } else {
                    lastMessage = "MICROPHONE PERMISSION REQUIRED"
                }
            }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initializeListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun initializeListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastMessage = "SPEECH RECOGNITION UNAVAILABLE"
            return
        }

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    currentState = JarvisState.LISTENING
                    lastMessage = "بسمعك يا فندم"
                }

                override fun onBeginningOfSpeech() {
                    currentState = JarvisState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    currentState = JarvisState.THINKING
                    lastMessage = "ثواني أفكر يا فندم"
                }

                override fun onError(error: Int) {
                    if (currentState != JarvisState.SPEAKING) {
                        currentState = JarvisState.WAITING
                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(restartListening, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val spokenText =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull()

                    if (spokenText.isNullOrBlank()) {
                        handler.postDelayed(restartListening, 700)
                        return
                    }

                    lastMessage = spokenText
                    currentState = JarvisState.THINKING
                    sendToJarvis(spokenText)
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        )

        startListening()
    }

    private fun startListening() {
        if (isFinishing || currentState == JarvisState.SPEAKING) return

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-EG")
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            handler.postDelayed(restartListening, 1500)
        }
    }

    private fun sendToJarvis(text: String) {
        currentState = JarvisState.THINKING
        lastMessage = "جاري تحليل كلامك..."

        Thread {
            var connection: HttpURLConnection? = null

            try {
                connection = URL(serverUrl).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 90000
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                val requestBody = JSONObject()
                    .put("text", text)
                    .toString()

                connection.outputStream.use {
                    it.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                val responseText =
                    (if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    })?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("Empty server response")

                val response = JSONObject(responseText)

                if (responseCode !in 200..299) {
                    throw Exception(
                        response.optString("details",
                            response.optString("error", "Server error"))
                    )
                }

                val reply = response.optString("reply")
                val audioBase64 = response.optString("audioBase64")

                if (reply.isBlank() || audioBase64.isBlank()) {
                    throw Exception("Incomplete server response")
                }

                val audioBytes =
                    Base64.decode(audioBase64, Base64.DEFAULT)

                val audioFile = File(cacheDir, "jarvis_reply.wav")
                audioFile.writeBytes(audioBytes)

                runOnUiThread {
                    if (!isFinishing) {
                        lastMessage = reply
                        playJarvisAudio(audioFile)
                    }
                }

            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing) {
                        currentState = JarvisState.WAITING
                        lastMessage = "حصل خطأ في الاتصال"
                        Toast.makeText(
                            this,
                            e.message ?: "Connection error",
                            Toast.LENGTH_LONG
                        ).show()

                        handler.postDelayed(restartListening, 2000)
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun playJarvisAudio(file: File) {
        try {
            mediaPlayer?.release()

            currentState = JarvisState.SPEAKING

            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)

                setOnCompletionListener {
                    release()
                    mediaPlayer = null
                    currentState = JarvisState.WAITING
                    lastMessage = "مستني أوامرك يا فندم"
                    handler.postDelayed(restartListening, 700)
                }

                setOnErrorListener { player, _, _ ->
                    player.release()
                    mediaPlayer = null
                    currentState = JarvisState.WAITING
                    handler.postDelayed(restartListening, 1000)
                    true
                }

                prepare()
                start()
            }

        } catch (e: Exception) {
            mediaPlayer?.release()
            mediaPlayer = null
            currentState = JarvisState.WAITING
            lastMessage = "تعذر تشغيل الصوت"
            handler.postDelayed(restartListening, 1500)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}

@Composable
fun JarvisApp(
    state: JarvisState,
    message: String
) {
    val gold = Color(0xFFFFB547)
    val background = Color(0xFF080B10)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = gold,
            background = background,
            surface = background
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "J.A.R.V.I.S",
                    color = gold,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "HOLOGRAPHIC INTERFACE",
                    color = Color(0xFFB87935),
                    fontSize = 11.sp,
                    letterSpacing = 3.sp
                )

                Spacer(modifier = Modifier.height(35.dp))

                JarvisOrb(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    state = state
                )

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = when (state) {
                        JarvisState.LISTENING -> "LISTENING"
                        JarvisState.THINKING -> "PROCESSING"
                        JarvisState.SPEAKING -> "SPEAKING"
                        JarvisState.WAITING -> "J.A.R.V.I.S ONLINE"
                    },
                    color = gold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    color = Color(0xFFB87935),
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(45.dp))

                Text(
                    text = "STARK INDUSTRIES • JARVIS CORE",
                    color = Color.DarkGray,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun JarvisOrb(
    modifier: Modifier = Modifier,
    state: JarvisState
) {
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state) {
        while (true) {
            val speed = when (state) {
                JarvisState.LISTENING -> 2.0f
                JarvisState.THINKING -> 3.5f
                JarvisState.SPEAKING -> 2.7f
                JarvisState.WAITING -> 1.2f
            }

            angle = (angle + speed) % 360f
            delay(16)
        }
    }

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) * 0.29f

        val gold = Color(0xFFFFB547)
        val orange = Color(0xFFFF7417)
        val paleGold = Color(0xFFFFE0A0)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x44FF9D32),
                    Color(0x18FF8C20),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 2.1f
            ),
            radius = radius * 2.1f,
            center = center
        )

        for (i in 0 until 5) {
            val rotation = angle *
                (if (i % 2 == 0) 1f else -0.65f) + i * 36f

            val ovalWidth = radius * (1.35f + i * 0.16f)
            val ovalHeight = radius * (0.48f + i * 0.09f)

            withTransform({ rotate(rotation, center) }) {
                drawOval(
                    color = gold.copy(alpha = 0.65f - i * 0.07f),
                    topLeft = Offset(
                        center.x - ovalWidth,
                        center.y - ovalHeight
                    ),
                    size = Size(ovalWidth * 2, ovalHeight * 2),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        for (i in 0 until 8) {
            val rotation = angle *
                (if (i % 2 == 0) 0.8f else -1.1f) + i * 22.5f

            withTransform({ rotate(rotation, center) }) {
                drawOval(
                    color = orange.copy(alpha = 0.35f),
                    topLeft = Offset(
                        center.x - radius,
                        center.y - radius * (0.35f + i * 0.035f)
                    ),
                    size = Size(
                        radius * 2,
                        radius * (0.7f + i * 0.07f)
                    ),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
        }

        for (i in 0 until 36) {
            val orbit = radius * (0.65f + (i % 5) * 0.19f)
            val radians = Math.toRadians(
                (angle * (if (i % 2 == 0) 1.5f else -1f) +
                    i * 10f).toDouble()
            )

            val x = center.x + cos(radians).toFloat() * orbit
            val y = center.y + sin(radians).toFloat() * orbit * 0.65f

            drawCircle(
                color = if (i % 3 == 0) paleGold else orange,
                radius = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                center = Offset(x, y)
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    paleGold,
                    gold,
                    orange.copy(alpha = 0.5f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 0.8f
            ),
            radius = radius * 0.8f,
            center = center
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius * 0.12f,
            center = center
        )

        for (i in 0 until 12) {
            val radians = Math.toRadians(
                (angle * 1.8f + i * 30f).toDouble()
            )

            val orbit = radius * 0.48f

            drawCircle(
                color = gold,
                radius = 2.dp.toPx(),
                center = Offset(
                    center.x + cos(radians).toFloat() * orbit,
                    center.y + sin(radians).toFloat() * orbit
                )
            )
        }
    }
}
