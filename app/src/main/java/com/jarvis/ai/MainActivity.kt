
package com.jarvis.ai

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import kotlin.math.*

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

    private var jarvisState by mutableStateOf(JarvisState.WAITING)
    private var introFinished by mutableStateOf(false)

    private val serverUrl =
        "https://jarvis-ai-server.astrogame373.workers.dev/chat"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    JarvisApp(
                        state = jarvisState,
                        onIntroFinished = {
                            introFinished = true
                            checkMicrophonePermission()
                        },
                        onInterrupt = {
                            interruptJarvis()
                        }
                    )
                }
            }
        }
    }

    private fun checkMicrophonePermission() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initializeSpeechRecognizer()
            startListening()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initializeSpeechRecognizer()
            startListening()
        }
    }

    private fun initializeSpeechRecognizer() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }

        speechRecognizer?.destroy()

        speechRecognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    jarvisState = JarvisState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    jarvisState = JarvisState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    jarvisState = JarvisState.THINKING
                }

                override fun onError(error: Int) {
                    if (introFinished) {
                        handler.postDelayed({
                            startListening()
                        }, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val spokenText = matches?.firstOrNull()

                    if (!spokenText.isNullOrBlank()) {
                        sendToServer(spokenText)
                    } else {
                        startListening()
                    }
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {}
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun startListening() {

        if (!introFinished) return

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        try {
            if (speechRecognizer == null) {
                initializeSpeechRecognizer()
            }

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
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
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            jarvisState = JarvisState.WAITING

            handler.postDelayed({
                startListening()
            }, 1500)
        }
    }

    private fun sendToServer(text: String) {

        jarvisState = JarvisState.THINKING

        Thread {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(serverUrl).openConnection()
                            as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                val requestBody = JSONObject()
                    .put("text", text)
                    .toString()

                connection.outputStream.use {
                    it.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response = responseStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: throw Exception("Empty server response")

                val json = JSONObject(response)

                if (!json.optBoolean("success", false)) {
                    throw Exception(
                        json.optString("error", "Server error")
                    )
                }

                val reply = json.optString("reply", "")
                val audioBase64 = json.optString("audioBase64", "")

                runOnUiThread {

                    if (audioBase64.isNotBlank()) {
                        playJarvisAudio(audioBase64)
                    } else {
                        jarvisState = JarvisState.WAITING
                        handler.postDelayed({
                            startListening()
                        }, 800)
                    }
                }

            } catch (e: Exception) {

                runOnUiThread {
                    jarvisState = JarvisState.WAITING

                    handler.postDelayed({
                        startListening()
                    }, 1500)
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
    }

    private fun playJarvisAudio(audioBase64: String) {

        try {

            mediaPlayer?.release()
            mediaPlayer = null

            val audioBytes = Base64.decode(
                audioBase64,
                Base64.DEFAULT
            )

            val audioFile = java.io.File(
                cacheDir,
                "jarvis_reply.mp3"
            )

            audioFile.writeBytes(audioBytes)

            mediaPlayer = MediaPlayer().apply {

                setDataSource(audioFile.absolutePath)

                setOnPreparedListener { player ->
                    jarvisState = JarvisState.SPEAKING
                    player.start()
                }

                setOnCompletionListener { player ->
                    player.release()
                    mediaPlayer = null

                    jarvisState = JarvisState.WAITING

                    handler.postDelayed({
                        startListening()
                    }, 700)
                }

                setOnErrorListener { player, _, _ ->

                    player.release()
                    mediaPlayer = null

                    jarvisState = JarvisState.WAITING

                    handler.postDelayed({
                        startListening()
                    }, 1000)

                    true
                }

                prepareAsync()
            }

        } catch (e: Exception) {

            mediaPlayer?.release()
            mediaPlayer = null

            jarvisState = JarvisState.WAITING

            handler.postDelayed({
                startListening()
            }, 1000)
        }
    }

    private fun interruptJarvis() {

        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}

        mediaPlayer?.release()
        mediaPlayer = null

        jarvisState = JarvisState.WAITING

        handler.postDelayed({
            startListening()
        }, 350)
    }

    override fun onDestroy() {
        super.onDestroy()

        handler.removeCallbacksAndMessages(null)

        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null

        mediaPlayer?.release()
        mediaPlayer = null
    }
}

/* ==============================
   JARVIS MAIN INTERFACE
================================ */

@Composable
fun JarvisApp(
    state: JarvisState,
    onIntroFinished: () -> Unit,
    onInterrupt: () -> Unit
) {

    var introStarted by remember { mutableStateOf(false) }
    var introComplete by remember { mutableStateOf(false) }

    val titleProgress by animateFloatAsState(
        targetValue = if (introComplete) 1f else 0f,
        animationSpec = tween(
            durationMillis = 1800,
            easing = FastOutSlowInEasing
        ),
        label = "title"
    )

    val orbProgress by animateFloatAsState(
        targetValue = if (introComplete) 1f else 0f,
        animationSpec = tween(
            durationMillis = 2400,
            delayMillis = 900,
            easing = FastOutSlowInEasing
        ),
        label = "orb"
    )

    LaunchedEffect(Unit) {

        introStarted = true

        kotlinx.coroutines.delay(1700)

        introComplete = true

        kotlinx.coroutines.delay(1900)

        onIntroFinished()
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {

        val screenHeight = maxHeight

        androidx.compose.foundation.text.BasicText(
            text = "JARVIS",
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = screenHeight * (0.39f - 0.39f * titleProgress))
                .graphicsLayer {
                    scaleX = 1f - (0.48f * titleProgress)
                    scaleY = 1f - (0.48f * titleProgress)
                    alpha = 1f - (0.15f * titleProgress)
                },
            style = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFDCEBFF),
                fontSize = androidx.compose.ui.unit.TextUnit(
                    48f,
                    androidx.compose.ui.unit.TextUnitType.Sp
                ),
                letterSpacing = androidx.compose.ui.unit.TextUnit(
                    9f,
                    androidx.compose.ui.unit.TextUnitType.Sp
                ),
                fontWeight = androidx.compose.ui.text.font.FontWeight.Light
            )
        )

        if (orbProgress > 0f) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(390.dp)
                    .align(Alignment.Center)
                    .graphicsLayer {
                        alpha = orbProgress
                        scaleX = 0.65f + orbProgress * 0.35f
                        scaleY = 0.65f + orbProgress * 0.35f
                    }
                    .clickable(
                        enabled = state == JarvisState.SPEAKING
                    ) {
                        onInterrupt()
                    },
                contentAlignment = Alignment.Center
            ) {

                JarvisNanoOrb(
                    state = state,
                    formation = orbProgress
                )
            }
        }
    }
}

/* ==============================
   NANO PARTICLE ORB
================================ */

data class NanoParticle(
    val x: Float,
    val y: Float,
    val z: Float
)

@Composable
fun JarvisNanoOrb(
    state: JarvisState,
    formation: Float
) {

    val particleCount = 360

    val particles = remember {

        List(particleCount) { index ->

            val y = 1f - (index.toFloat() / (particleCount - 1)) * 2f

            val radius = sqrt(1f - y * y)

            val angle = index * 2.399963f

            NanoParticle(
                x = cos(angle) * radius,
                y = y,
                z = sin(angle) * radius
            )
        }
    }

    val transition = rememberInfiniteTransition(
        label = "nano"
    )

    val animatedPhase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 9000,
                easing = androidx.compose.animation.core.LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val speakingPulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1100,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val isSpeaking = state == JarvisState.SPEAKING

    val rotation = if (isSpeaking) animatedPhase * 0.22f else 0f
    val pulse = if (isSpeaking) speakingPulse else 1f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
    ) {

        val centerX = size.width / 2f
        val centerY = size.height / 2f

        val orbRadius = size.minDimension * 0.36f

        val blue = Color(0xFF8BCBFF)
        val silver = Color(0xFFDCEBFF)
        val deepBlue = Color(0xFF3989D6)

        val projected = ArrayList<Offset>(particleCount)

        particles.forEach { particle ->

            val rotatedX =
                particle.x * cos(rotation) -
                particle.z * sin(rotation)

            val rotatedZ =
                particle.x * sin(rotation) +
                particle.z * cos(rotation)

            val perspective = 1f / (1.5f - rotatedZ * 0.35f)

            val x = centerX +
                rotatedX * orbRadius * perspective * pulse * formation

            val y = centerY +
                particle.y * orbRadius * perspective * pulse * formation

            projected.add(Offset(x, y))
        }

        // Soft holographic glow

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x332E8FE8),
                    Color(0x183B9EEB),
                    Color.Transparent
                ),
                center = Offset(centerX, centerY),
                radius = orbRadius * 1.55f
            ),
            radius = orbRadius * 1.55f
        )

        // Particle connections

        for (i in particles.indices) {

            val first = projected[i]

            val neighbors = listOf(
                (i + 1) % particleCount,
                (i + 7) % particleCount
            )

            neighbors.forEach { next ->

                val second = projected[next]

                drawLine(
                    color = blue.copy(alpha = 0.20f * formation),
                    start = first,
                    end = second,
                    strokeWidth = 0.65f
                )
            }
        }

        // Individual nano particles

        particles.forEachIndexed { index, particle ->

            val point = projected[index]

            val depthAlpha =
                (0.45f + (particle.z + 1f) * 0.25f) * formation

            val particleSize =
                (1.2f + (particle.z + 1f) * 1.2f) * formation

            val particleColor =
                if (index % 5 == 0) silver else blue

            drawCircle(
                color = particleColor.copy(
                    alpha = depthAlpha.coerceIn(0f, 1f)
                ),
                radius = particleSize,
                center = point
            )

            if (index % 17 == 0) {

                drawCircle(
                    color = deepBlue.copy(alpha = 0.18f),
                    radius = particleSize * 4f,
                    center = point
                )
            }
        }

        // Transparent spherical boundary

        drawCircle(
            color = silver.copy(alpha = 0.28f * formation),
            radius = orbRadius * pulse,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1.1f)
        )

        // Outer holographic ring

        drawCircle(
            color = blue.copy(alpha = 0.14f * formation),
            radius = orbRadius * 1.12f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 0.7f)
        )
    }
}
