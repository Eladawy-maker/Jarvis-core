
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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

enum class JarvisState {
    LISTENING,
    THINKING,
    SPEAKING,
    WAITING
}

class MainActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var mediaPlayer: MediaPlayer? = null

    private var currentState by mutableStateOf(JarvisState.WAITING)
    private var lastMessage by mutableStateOf("SYSTEM READY")

    private val serverUrl =
        "https://jarvis-ai-server.astrogame373.workers.dev/chat"

    private var isActivityDestroyed = false

    private val restartListening = Runnable {
        if (
            !isFinishing &&
            !isActivityDestroyed &&
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
                message = lastMessage,
                onInterrupt = {
                    interruptJarvis()
                }
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

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeListening()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // =========================================
    // SPEECH RECOGNITION
    // =========================================

    private fun initializeListening() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            lastMessage = "SPEECH RECOGNITION UNAVAILABLE"
            return
        }

        try {

            speechRecognizer?.destroy()

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (currentState != JarvisState.SPEAKING) {
                            currentState = JarvisState.LISTENING
                            lastMessage = "بسمعك يا فندم"
                        }
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

                        if (
                            currentState != JarvisState.SPEAKING &&
                            currentState != JarvisState.THINKING
                        ) {
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

        } catch (e: Exception) {

            currentState = JarvisState.WAITING
            lastMessage = "MICROPHONE INITIALIZATION ERROR"

            handler.postDelayed(restartListening, 1500)
        }
    }

    private fun startListening() {

        if (
            isFinishing ||
            isActivityDestroyed ||
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

                    putExtra(
                        RecognizerIntent.EXTRA_CALLING_PACKAGE,
                        packageName
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        ""
                    )
                }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {

            currentState = JarvisState.WAITING
            handler.removeCallbacks(restartListening)
            handler.postDelayed(restartListening, 1500)
        }
    }

    // =========================================
    // INTERRUPT JARVIS
    // =========================================

    private fun interruptJarvis() {

        if (currentState != JarvisState.SPEAKING) return

        handler.removeCallbacks(restartListening)

        // إيقاف صوت جارفيس فورًا
        try {
            mediaPlayer?.let { player ->
                try {
                    player.stop()
                } catch (_: Exception) {
                }

                player.release()
            }
        } catch (_: Exception) {
        }

        mediaPlayer = null

        // إلغاء أي جلسة استماع قديمة
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        currentState = JarvisState.WAITING
        lastMessage = "اتفضل يا فندم، سامعك"

        // إعادة تشغيل الاستماع
        handler.postDelayed({
            if (!isFinishing && !isActivityDestroyed) {
                startListening()
            }
        }, 350)
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
                    (
                        if (responseCode in 200..299) {
                            connection.inputStream
                        } else {
                            connection.errorStream
                        }
                    )?.bufferedReader()?.use {
                        it.readText()
                    } ?: throw Exception("Empty server response")

                val response = JSONObject(responseText)

                if (responseCode !in 200..299) {

                    val details = response.optString(
                        "details",
                        response.optString("error", "Server error")
                    )

                    throw Exception("HTTP $responseCode: $details")
                }

                if (!response.optBoolean("success", false)) {
                    throw Exception(
                        response.optString("error", "JARVIS SERVER ERROR")
                    )
                }

                val reply = response.optString("reply", "")
                val audioBase64 = response.optString("audioBase64", "")
                val audioError = response.optString("audioError", "")

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

                val audioFile =
                    File(cacheDir, "jarvis_reply.mp3")

                audioFile.writeBytes(audioBytes)

                runOnUiThread {

                    if (!isFinishing && !isActivityDestroyed) {
                        lastMessage = reply
                        playJarvisAudio(audioFile)
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {

                    if (!isFinishing && !isActivityDestroyed) {

                        currentState = JarvisState.WAITING
                        lastMessage = "حصل خطأ في الاتصال"

                        Toast.makeText(
                            this,
                            e.message ?: "Connection error",
                            Toast.LENGTH_LONG
                        ).show()

                        handler.removeCallbacks(restartListening)
                        handler.postDelayed(restartListening, 2000)
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

            val player = MediaPlayer()
            mediaPlayer = player

            player.setOnPreparedListener { preparedPlayer ->

                if (
                    !isFinishing &&
                    !isActivityDestroyed &&
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

                if (!isFinishing && !isActivityDestroyed) {

                    currentState = JarvisState.WAITING
                    lastMessage = "مستني أوامرك يا فندم"

                    handler.removeCallbacks(restartListening)
                    handler.postDelayed(restartListening, 900)
                }
            }

            player.setOnErrorListener { failedPlayer, _, _ ->

                failedPlayer.release()

                if (mediaPlayer === failedPlayer) {
                    mediaPlayer = null
                }

                if (!isFinishing && !isActivityDestroyed) {

                    currentState = JarvisState.WAITING
                    lastMessage = "تعذر تشغيل الصوت"

                    handler.removeCallbacks(restartListening)
                    handler.postDelayed(restartListening, 1200)
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

            handler.postDelayed(restartListening, 1500)
        }
    }

    // =========================================
    // DESTROY
    // =========================================

    override fun onDestroy() {

        isActivityDestroyed = true

        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null

        super.onDestroy()
    }
}

// =====================================================
// JARVIS MAIN INTERFACE
// =====================================================

@Composable
fun JarvisApp(
    state: JarvisState,
    message: String,
    onInterrupt: () -> Unit
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

                // HEADER
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
                                        if (state == JarvisState.WAITING) {
                                            gold
                                        } else {
                                            Color(0xFFFF8A26)
                                        },
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

                // GOLD DIVIDER
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

                // MAIN ORB
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {

                    JarvisOrb(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .clickable(
                                enabled = state == JarvisState.SPEAKING,
                                onClick = onInterrupt
                            ),
                        state = state
                    )
                }

                // STATUS
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

                // INTERRUPT INSTRUCTION
                if (state == JarvisState.SPEAKING) {

                    Spacer(modifier = Modifier.height(9.dp))

                    Text(
                        text = "اضغط على الكرة لمقاطعة جارفيس",
                        color = Color(0xFFFFB84D),
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.clickable {
                            onInterrupt()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // SYSTEM PANELS
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
                        value = if (state == JarvisState.SPEAKING) {
                            "SPEAKING"
                        } else {
                            "READY"
                        }
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

// =====================================================
// HUD PANEL
// =====================================================

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
// JARVIS 3D ORB
// =====================================================

@Composable
fun JarvisOrb(
    modifier: Modifier = Modifier,
    state: JarvisState
) {

    // لا توجد حركة دورية إلا أثناء كلام جارفيس
    val pulse = if (state == JarvisState.SPEAKING) {

        val transition = rememberInfiniteTransition(
            label = "JarvisSpeaking"
        )

        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 900,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "OrbPulse"
        ).value

    } else {
        0f
    }

    Canvas(modifier = modifier) {

        val center = Offset(
            size.width / 2f,
            size.height / 2f
        )

        val baseRadius =
            minOf(size.width, size.height) * 0.27f

        val radius =
            baseRadius * (
                if (state == JarvisState.SPEAKING) {
                    1f + pulse * 0.035f
                } else {
                    1f
                }
            )

        val gold = Color(0xFFFFB84D)
        val brightGold = Color(0xFFFFE5B0)
        val orange = Color(0xFFFF841F)
        val darkGold = Color(0xFF5A3215)

        val glowStrength = when (state) {
            JarvisState.WAITING -> 0.22f
            JarvisState.LISTENING -> 0.32f
            JarvisState.THINKING -> 0.38f
            JarvisState.SPEAKING -> 0.42f + pulse * 0.25f
        }

        val glowRadius =
            radius * if (state == JarvisState.SPEAKING) {
                1.9f + pulse * 0.2f
            } else {
                1.7f
            }

        // OUTER GLOW
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    orange.copy(alpha = glowStrength),
                    gold.copy(alpha = glowStrength * 0.35f),
                    Color.Transparent
                ),
                center = center,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = center
        )

        // OUTER SHELL
        drawCircle(
            color = darkGold.copy(alpha = 0.9f),
            radius = radius * 1.04f,
            center = center
        )

        // 3D SPHERE BODY
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFFFF4D8),
                    0.12f to brightGold,
                    0.32f to gold,
                    0.58f to Color(0xFFCF7827),
                    0.78f to Color(0xFF713A18),
                    1.0f to Color(0xFF160E0A)
                ),
                center = Offset(
                    center.x - radius * 0.32f,
                    center.y - radius * 0.38f
                ),
                radius = radius * 1.65f
            ),
            radius = radius,
            center = center
        )

        // SPHERE EDGE
        drawCircle(
            color = gold.copy(alpha = 0.65f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // SURFACE REFLECTION
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.75f),
                    brightGold.copy(alpha = 0.25f),
                    Color.Transparent
                ),
                center = Offset(
                    center.x - radius * 0.34f,
                    center.y - radius * 0.38f
                ),
                radius = radius * 0.75f
            ),
            radius = radius * 0.75f,
            center = Offset(
                center.x - radius * 0.34f,
                center.y - radius * 0.38f
            )
        )

        // INNER ENERGY CORE
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f),
                    brightGold.copy(alpha = 0.55f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 0.48f
            ),
            radius = radius * 0.48f,
            center = center
        )

        // LIGHT REFLECTION
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = radius * 0.045f,
            center = Offset(
                center.x - radius * 0.32f,
                center.y - radius * 0.39f
            )
        )

        // SPEAKING ENERGY RING
        if (state == JarvisState.SPEAKING) {

            drawCircle(
                color = brightGold.copy(
                    alpha = 0.25f + pulse * 0.35f
                ),
                radius = radius * (1.12f + pulse * 0.12f),
                center = center,
                style = Stroke(
                    width = (1.5f + pulse * 2f).dp.toPx()
                )
            )
        }
    }
}
