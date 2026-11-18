
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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // JARVIS AI SERVER
    private val serverUrl =
        "https://jarvis-ai-server.astrogame373.workers.dev/chat"

    private val restartListening = Runnable {
        if (!isFinishing &&
            currentState != JarvisState.SPEAKING &&
            currentState != JarvisState.THINKING
        ) {
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

                    if (currentState != JarvisState.SPEAKING &&
                        currentState != JarvisState.THINKING
                    ) {
                        currentState = JarvisState.WAITING

                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(restartListening, 1200)
                    }
                }

                override fun onResults(results: Bundle?) {

                    val spokenText =
                        results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull()

                    if (spokenText.isNullOrBlank()) {
                        currentState = JarvisState.WAITING
                        handler.postDelayed(restartListening, 700)
                        return
                    }

                    handler.removeCallbacks(restartListening)

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

        if (isFinishing ||
            currentState == JarvisState.SPEAKING ||
            currentState == JarvisState.THINKING
        ) return

        try {

            val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "ar-EG"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        "ar-EG"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        1
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )
                }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {

            currentState = JarvisState.WAITING
            handler.postDelayed(restartListening, 1500)
        }
    }

    // =========================================
    // SEND MESSAGE TO JARVIS SERVER
    // =========================================

    private fun sendToJarvis(text: String) {

        currentState = JarvisState.THINKING
        lastMessage = "جاري تحليل كلامك..."

        handler.removeCallbacks(restartListening)

        Thread {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(serverUrl).openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 120000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val requestBody =
                    JSONObject()
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
                    })?.bufferedReader()?.use {
                        it.readText()
                    } ?: throw Exception("Empty server response")

                val response = JSONObject(responseText)

                if (responseCode !in 200..299) {

                    val details = response.optString(
                        "details",
                        response.optString(
                            "error",
                            "Server error"
                        )
                    )

                    throw Exception("HTTP $responseCode: $details")
                }

                val success = response.optBoolean("success", false)

                if (!success) {
                    throw Exception(
                        response.optString(
                            "error",
                            "JARVIS SERVER ERROR"
                        )
                    )
                }

                val reply = response.optString("reply", "")

                val audioBase64 =
                    response.optString("audioBase64", "")

                val audioMimeType =
                    response.optString("audioMimeType", "")

                val audioError =
                    response.optString("audioError", "")

                if (reply.isBlank()) {
                    throw Exception("Server returned an empty reply")
                }

                if (audioBase64.isBlank()) {
                    throw Exception(
                        if (audioError.isNotBlank() && audioError != "null") {
                            audioError
                        } else {
                            "Server returned no audio"
                        }
                    )
                }

                val audioBytes =
                    Base64.decode(audioBase64, Base64.DEFAULT)

                if (audioBytes.isEmpty()) {
                    throw Exception("Audio data is empty")
                }

                // ELEVENLABS RETURNS MP3
                val audioFile =
                    File(cacheDir, "jarvis_reply.mp3")

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

                        handler.postDelayed(
                            restartListening,
                            2000
                        )
                    }
                }

            } finally {

                connection?.disconnect()
            }

        }.start()
    }

    // =========================================
    // PLAY ELEVENLABS AUDIO
    // =========================================

    private fun playJarvisAudio(file: File) {

        try {

            mediaPlayer?.release()
            mediaPlayer = null

            currentState = JarvisState.SPEAKING

            lastMessage = lastMessage

            val player = MediaPlayer()

            mediaPlayer = player

            player.setOnPreparedListener { preparedPlayer ->

                if (!isFinishing &&
                    mediaPlayer === preparedPlayer
                ) {
                    preparedPlayer.start()
                } else {
                    preparedPlayer.release()
                }
            }

            player.setOnCompletionListener { completedPlayer ->

                completedPlayer.release()

                if (mediaPlayer === completedPlayer) {
                    mediaPlayer = null
                }

                if (!isFinishing) {

                    currentState = JarvisState.WAITING

                    lastMessage = "مستني أوامرك يا فندم"

                    handler.removeCallbacks(restartListening)

                    handler.postDelayed(
                        restartListening,
                        900
                    )
                }
            }

            player.setOnErrorListener { failedPlayer, _, _ ->

                failedPlayer.release()

                if (mediaPlayer === failedPlayer) {
                    mediaPlayer = null
                }

                if (!isFinishing) {

                    currentState = JarvisState.WAITING

                    lastMessage = "تعذر تشغيل الصوت"

                    handler.postDelayed(
                        restartListening,
                        1200
                    )
                }

                true
            }

            player.setDataSource(file.absolutePath)

            player.prepareAsync()

        } catch (e: Exception) {

            mediaPlayer?.release()
            mediaPlayer = null

            currentState = JarvisState.WAITING
            lastMessage = "تعذر تشغيل الصوت"

            Toast.makeText(
                this,
                e.message ?: "Audio playback error",
                Toast.LENGTH_LONG
            ).show()

            handler.postDelayed(
                restartListening,
                1500
            )
        }
    }

    override fun onDestroy() {

        handler.removeCallbacksAndMessages(null)

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        mediaPlayer?.release()
        mediaPlayer = null

        super.onDestroy()
    }
}

// =====================================================
// JARVIS CINEMATIC INTERFACE
// =====================================================

@Composable
fun JarvisApp(
    state: JarvisState,
    message: String
) {

    val gold = Color(0xFFFFB84D)
    val dimGold = Color(0xFF9C672B)
    val background = Color(0xFF05070A)
    val panel = Color(0xFF0C1118)

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = gold,
            background = background,
            surface = panel
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
                    .padding(
                        horizontal = 20.dp,
                        vertical = 18.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {

                    Column {

                        Text(
                            text = "STARK INDUSTRIES",
                            color = dimGold,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )

                        Text(
                            text = "J.A.R.V.I.S",
                            color = gold,
                            fontSize = 27.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.End
                    ) {

                        Text(
                            text = "CORE SYSTEM",
                            color = dimGold,
                            fontSize = 9.sp,
                            letterSpacing = 2.sp
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        if (state == JarvisState.WAITING)
                                            gold
                                        else
                                            Color(0xFFFF8A26),
                                        CircleShape
                                    )
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            Text(
                                text = "ONLINE",
                                color = gold,
                                fontSize = 11.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    dimGold,
                                    gold,
                                    dimGold,
                                    Color.Transparent
                                )
                            )
                        )
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {

                    Text(
                        text = "PERSONAL AI",
                        color = Color(0xFF80603B),
                        fontSize = 9.sp,
                        letterSpacing = 1.5.sp
                    )

                    Text(
                        text = "VOICE INTERFACE  //  ACTIVE",
                        color = Color(0xFF80603B),
                        fontSize = 9.sp,
                        letterSpacing = 1.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {

                    JarvisOrb(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        state = state
                    )
                }

                Text(
                    text = when (state) {
                        JarvisState.LISTENING -> "LISTENING"
                        JarvisState.THINKING -> "ANALYZING"
                        JarvisState.SPEAKING -> "RESPONDING"
                        JarvisState.WAITING -> "STANDBY"
                    },
                    color = gold,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = message,
                    color = Color(0xFFD0A36B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {

                    HudPanel(
                        modifier = Modifier.weight(1f),
                        title = "A.I CORE",
                        value = "ACTIVE"
                    )

                    HudPanel(
                        modifier = Modifier.weight(1f),
                        title = "VOICE",
                        value = "READY"
                    )

                    HudPanel(
                        modifier = Modifier.weight(1f),
                        title = "NETWORK",
                        value = "CONNECTED"
                    )
                }

                Spacer(modifier = Modifier.height(17.dp))

                Text(
                    text = "JARVIS CORE  •  STARK TECHNOLOGY",
                    color = Color(0xFF55432E),
                    fontSize = 9.sp,
                    letterSpacing = 2.sp
                )
            }
        }
    }
}

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    title: String,
    value: String
) {

    Column(
        modifier = modifier
            .background(Color(0xFF0C1118))
            .padding(
                vertical = 12.dp,
                horizontal = 7.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = title,
            color = Color(0xFF9A713D),
            fontSize = 8.sp,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = value,
            color = Color(0xFFFFB84D),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// =====================================================
// ARC REACTOR ANIMATION
// =====================================================

@Composable
fun JarvisOrb(
    modifier: Modifier = Modifier,
    state: JarvisState
) {

    var angle by remember {
        mutableFloatStateOf(0f)
    }

    var pulse by remember {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(state) {

        while (true) {

            val speed = when (state) {
                JarvisState.LISTENING -> 2.8f
                JarvisState.THINKING -> 5.0f
                JarvisState.SPEAKING -> 3.8f
                JarvisState.WAITING -> 0.8f
            }

            angle = (angle + speed) % 360f

            pulse = (
                sin(Math.toRadians(angle.toDouble())).toFloat() + 1f
            ) / 2f

            delay(16)
        }
    }

    Canvas(modifier = modifier) {

        val center = Offset(
            size.width / 2f,
            size.height / 2f
        )

        val radius =
            minOf(size.width, size.height) * 0.27f

        val gold = Color(0xFFFFB84D)
        val brightGold = Color(0xFFFFE2A1)
        val orange = Color(0xFFFF7417)
        val darkGold = Color(0xFF70451E)

        val glow = 0.65f + pulse * 0.35f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x55FF9B30),
                    Color(0x22FF8C20),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 2.3f
            ),
            radius = radius * 2.3f,
            center = center
        )

        for (i in 0 until 4) {

            val r = radius * (1.75f + i * 0.13f)

            drawCircle(
                color = darkGold.copy(alpha = 0.55f),
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        for (i in 0 until 3) {

            val ringRadius =
                radius * (1.55f + i * 0.17f)

            withTransform({

                rotate(
                    angle * (if (i % 2 == 0) 1f else -0.7f) + i * 55f,
                    center
                )

            }) {

                drawArc(
                    color = gold.copy(alpha = 0.9f - i * 0.15f),
                    startAngle = 20f,
                    sweepAngle = 105f,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - ringRadius,
                        center.y - ringRadius
                    ),
                    size = Size(
                        ringRadius * 2,
                        ringRadius * 2
                    ),
                    style = Stroke(width = 3.dp.toPx())
                )

                drawArc(
                    color = orange.copy(alpha = 0.75f),
                    startAngle = 205f,
                    sweepAngle = 65f,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - ringRadius,
                        center.y - ringRadius
                    ),
                    size = Size(
                        ringRadius * 2,
                        ringRadius * 2
                    ),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        for (i in 0 until 8) {

            val rotation =
                angle * (if (i % 2 == 0) 1f else -1f) + i * 45f

            withTransform({
                rotate(rotation, center)
            }) {

                drawArc(
                    color = gold.copy(alpha = 0.65f),
                    startAngle = 0f,
                    sweepAngle = 24f,
                    useCenter = false,
                    topLeft = Offset(
                        center.x - radius * 1.3f,
                        center.y - radius * 1.3f
                    ),
                    size = Size(
                        radius * 2.6f,
                        radius * 2.6f
                    ),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }

        for (i in 0 until 48) {

            val radians = Math.toRadians(
                (i * 7.5f + angle * 0.3f).toDouble()
            )

            val inner = radius * 1.9f

            val outer =
                if (i % 4 == 0)
                    radius * 2.08f
                else
                    radius * 1.98f

            val x1 =
                center.x + cos(radians).toFloat() * inner

            val y1 =
                center.y + sin(radians).toFloat() * inner

            val x2 =
                center.x + cos(radians).toFloat() * outer

            val y2 =
                center.y + sin(radians).toFloat() * outer

            drawLine(
                color = gold.copy(
                    alpha = if (i % 4 == 0) 0.85f else 0.3f
                ),
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth =
                    if (i % 4 == 0)
                        2.dp.toPx()
                    else
                        1.dp.toPx()
            )
        }

        for (i in 0 until 3) {

            val r = radius * (0.9f + i * 0.16f)

            drawCircle(
                color = gold.copy(alpha = 0.8f - i * 0.15f),
                radius = r,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White,
                    brightGold,
                    gold,
                    orange.copy(alpha = 0.8f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * (0.78f + pulse * 0.08f)
            ),
            radius = radius * (0.78f + pulse * 0.08f),
            center = center
        )

        drawCircle(
            color = Color.White.copy(alpha = 0.95f),
            radius = radius * 0.16f,
            center = center
        )

        drawCircle(
            color = brightGold.copy(alpha = glow),
            radius = radius * 0.34f,
            center = center,
            style = Stroke(width = 3.dp.toPx())
        )

        for (i in 0 until 32) {

            val orbit =
                radius * (1.15f + (i % 4) * 0.22f)

            val radians = Math.toRadians(
                (
                    angle * (if (i % 2 == 0) 1.5f else -1f) +
                        i * 11.25f
                    ).toDouble()
            )

            val x =
                center.x + cos(radians).toFloat() * orbit

            val y =
                center.y + sin(radians).toFloat() * orbit

            drawCircle(
                color = if (i % 3 == 0)
                    brightGold
                else
                    orange,
                radius = if (i % 3 == 0)
                    3.dp.toPx()
                else
                    1.5.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}
