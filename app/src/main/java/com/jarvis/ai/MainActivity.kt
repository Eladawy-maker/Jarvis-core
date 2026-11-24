
package com.jarvis.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
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
    private var textToSpeech: TextToSpeech? = null

    private val networkExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    private var jarvisState by mutableStateOf(JarvisState.WAITING)

    private var introFinished = false
    private var destroyed = false

    private var recognizerActive = false
    private var requestInFlight = false
    private var speechInProgress = false

    private var ttsReady = false
    private var retryDelay = 1200L
    private var utteranceCounter = 0

    private val serverUrl =
        "https://jarvis-ai-server.astrogame373.workers.dev/chat"

    private val listenRunnable = Runnable {
        startListening()
    }

    private data class ServerReply(
        val text: String,
        val audioBase64: String,
        val audioMimeType: String,
        val audioError: String
    )

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {
                initializeSpeechRecognizer()
                startListening()
            } else {
                showMessage("لازم تسمح لجارفيس باستخدام الميكروفون.")
                jarvisState = JarvisState.WAITING
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        initializeTextToSpeech()

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

    // ==============================
    // TEXT TO SPEECH FALLBACK
    // ==============================

    private fun initializeTextToSpeech() {

        textToSpeech = TextToSpeech(this) { status ->

            if (status == TextToSpeech.SUCCESS) {

                val result = textToSpeech?.setLanguage(
                    Locale("ar", "EG")
                )

                ttsReady =
                    result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

                textToSpeech?.setSpeechRate(0.95f)
                textToSpeech?.setPitch(0.95f)

                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {
                            runOnUiThread {
                                if (!destroyed) {
                                    speechInProgress = true
                                    jarvisState = JarvisState.SPEAKING
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            runOnUiThread {
                                finishSpeaking()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                finishSpeaking()
                            }
                        }

                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int
                        ) {
                            runOnUiThread {
                                finishSpeaking()
                            }
                        }
                    }
                )
            } else {
                ttsReady = false
            }
        }
    }

    private fun speakFallback(text: String) {

        if (text.isBlank()) {
            showMessage("السيرفر لم يرجع ردًا صوتيًا أو نصيًا.")
            finishSpeaking()
            return
        }

        stopMediaPlayer()

        if (!ttsReady || textToSpeech == null) {
            showMessage(text)
            finishSpeaking()
            return
        }

        try {
            speechInProgress = true
            jarvisState = JarvisState.SPEAKING

            utteranceCounter++

            val utteranceId = "jarvis_$utteranceCounter"

            val result = textToSpeech?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )

            if (result == TextToSpeech.ERROR) {
                speechInProgress = false
                showMessage(text)
                finishSpeaking()
            }

        } catch (e: Exception) {
            speechInProgress = false
            showMessage(text)
            finishSpeaking()
        }
    }

    // ==============================
    // MICROPHONE PERMISSION
    // ==============================

    private fun checkMicrophonePermission() {

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeSpeechRecognizer()
            startListening()
        } else {
            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }

    // ==============================
    // SPEECH RECOGNIZER
    // ==============================

    private fun initializeSpeechRecognizer() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showMessage("خدمة التعرف على الكلام غير متاحة على الجهاز.")
            return
        }

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null

            recognizerActive = false

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        if (!destroyed) {
                            jarvisState = JarvisState.LISTENING
                        }
                    }

                    override fun onBeginningOfSpeech() {
                        if (!destroyed) {
                            jarvisState = JarvisState.LISTENING
                        }
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        if (!destroyed) {
                            jarvisState = JarvisState.THINKING
                        }
                    }

                    override fun onError(error: Int) {

                        recognizerActive = false

                        if (destroyed) return

                        if (requestInFlight || speechInProgress) {
                            return
                        }

                        jarvisState = JarvisState.WAITING

                        val delayTime = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L

                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 2500L

                            else -> 1200L
                        }

                        scheduleListening(delayTime)
                    }

                    override fun onResults(results: Bundle?) {

                        recognizerActive = false

                        if (destroyed) return

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spokenText = matches?.firstOrNull()?.trim()

                        if (!spokenText.isNullOrBlank()) {
                            retryDelay = 1200L
                            sendToServer(spokenText)
                        } else {
                            scheduleListening(500L)
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

        } catch (e: Exception) {
            recognizerActive = false
            showMessage("تعذر تشغيل خدمة الميكروفون.")
            scheduleListening(2000L)
        }
    }

    // ==============================
    // START LISTENING
    // ==============================

    private fun startListening() {

        if (destroyed || !introFinished) return

        if (
            requestInFlight ||
            speechInProgress ||
            recognizerActive
        ) return

        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        if (speechRecognizer == null) {
            initializeSpeechRecognizer()
        }

        val recognizer = speechRecognizer ?: return

        try {

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
            }

            handler.removeCallbacks(listenRunnable)

            recognizerActive = true
            jarvisState = JarvisState.LISTENING

            recognizer.startListening(intent)

        } catch (e: Exception) {

            recognizerActive = false
            jarvisState = JarvisState.WAITING

            scheduleListening(1800L)
        }
    }

    private fun scheduleListening(delayMillis: Long) {

        if (destroyed || !introFinished) return

        handler.removeCallbacks(listenRunnable)

        handler.postDelayed(
            listenRunnable,
            delayMillis
        )
    }

    // ==============================
    // SERVER COMMUNICATION
    // ==============================

    private fun sendToServer(text: String) {

        if (destroyed || requestInFlight) return

        requestInFlight = true
        recognizerActive = false

        handler.removeCallbacks(listenRunnable)

        jarvisState = JarvisState.THINKING

        networkExecutor.execute {

            var connection: HttpURLConnection? = null

            try {

                connection =
                    URL(serverUrl).openConnection() as HttpURLConnection

                activeConnection = connection

                connection.requestMethod = "POST"
                connection.connectTimeout = 20000
                connection.readTimeout = 60000
                connection.doOutput = true
                connection.doInput = true
                connection.useCaches = false

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val requestBody = JSONObject()
                    .put("text", text)
                    .toString()

                connection.outputStream.use { output ->
                    output.write(
                        requestBody.toByteArray(Charsets.UTF_8)
                    )
                    output.flush()
                }

                val responseCode = connection.responseCode

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response = responseStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?: throw Exception(
                        "السيرفر لم يرجع أي استجابة. HTTP $responseCode"
                    )

                if (responseCode !in 200..299) {
                    throw Exception(
                        "خطأ من السيرفر: HTTP $responseCode"
                    )
                }

                val json = JSONObject(response)

                if (!json.optBoolean("success", false)) {
                    throw Exception(
                        json.optString(
                            "error",
                            "السيرفر لم يستطع تنفيذ الطلب."
                        )
                    )
                }

                val reply = ServerReply(
                    text = json.optString("reply", ""),
                    audioBase64 = json.optString("audioBase64", ""),
                    audioMimeType = json.optString(
                        "audioMimeType",
                        json.optString("mimeType", "audio/mpeg")
                    ),
                    audioError = json.optString("audioError", "")
                )

                runOnUiThread {

                    if (destroyed) return@runOnUiThread

                    requestInFlight = false
                    retryDelay = 1200L

                    handleServerReply(reply)
                }

            } catch (e: Exception) {

                val errorMessage =
                    e.message ?: "حدث خطأ غير معروف أثناء الاتصال."

                runOnUiThread {

                    if (destroyed) return@runOnUiThread

                    requestInFlight = false

                    jarvisState = JarvisState.WAITING

                    showMessage(
                        "تعذر الاتصال بجارفيس: $errorMessage"
                    )

                    scheduleListening(1800L)
                }

            } finally {

                connection?.disconnect()

                if (activeConnection === connection) {
                    activeConnection = null
                }
            }
        }
    }

    // ==============================
    // HANDLE SERVER RESPONSE
    // ==============================

    private fun handleServerReply(reply: ServerReply) {

        if (reply.audioBase64.isBlank()) {

            val reason = reply.audioError.ifBlank {
                "السيرفر لم يرسل ملف الصوت."
            }

            showMessage("مشكلة صوت ElevenLabs: ${reason.take(180)}")

            speakFallback(reply.text)
            return
        }

        try {

            val audioBytes = Base64.decode(
                reply.audioBase64,
                Base64.DEFAULT
            )

            if (audioBytes.isEmpty()) {
                throw Exception("ملف الصوت وصل فارغًا.")
            }

            playJarvisAudio(
                audioBytes,
                reply.audioMimeType,
                reply.text
            )

        } catch (e: Exception) {

            showMessage(
                "خطأ في قراءة صوت ElevenLabs: ${e.message ?: "غير معروف"}"
            )

            speakFallback(reply.text)
        }
    }

    // ==============================
    // AUDIO MIME TYPE
    // ==============================

    private fun getAudioExtension(mimeType: String): String {

        return when (mimeType.lowercase(Locale.ROOT)) {

            "audio/mpeg",
            "audio/mp3" -> "mp3"

            "audio/wav",
            "audio/x-wav" -> "wav"

            "audio/ogg",
            "application/ogg" -> "ogg"

            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a" -> "m4a"

            "audio/aac" -> "aac"

            "audio/3gpp" -> "3gp"

            else -> "audio"
        }
    }

    // ==============================
    // PLAY SERVER AUDIO
    // ==============================

    private fun playJarvisAudio(
        audioBytes: ByteArray,
        mimeType: String,
        fallbackText: String
    ) {

        if (destroyed) return

        try {

            stopMediaPlayer()

            textToSpeech?.stop()

            val extension = getAudioExtension(mimeType)

            val audioFile = File(
                cacheDir,
                "jarvis_reply.$extension"
            )

            audioFile.writeBytes(audioBytes)

            val player = MediaPlayer()

            mediaPlayer = player

            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(
                        AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()
            )

            player.setDataSource(audioFile.absolutePath)

            player.setOnPreparedListener { preparedPlayer ->

                if (destroyed) {
                    preparedPlayer.release()
                    return@setOnPreparedListener
                }

                speechInProgress = true
                jarvisState = JarvisState.SPEAKING

                preparedPlayer.start()
            }

            player.setOnCompletionListener { completedPlayer ->

                completedPlayer.release()

                if (mediaPlayer === completedPlayer) {
                    mediaPlayer = null
                }

                finishSpeaking()
            }

            player.setOnErrorListener { failedPlayer, what, extra ->

                failedPlayer.release()

                if (mediaPlayer === failedPlayer) {
                    mediaPlayer = null
                }

                speechInProgress = false

                if (!destroyed) {
                    showMessage(
                        "خطأ تشغيل صوت جارفيس: what=$what extra=$extra"
                    )
                    speakFallback(fallbackText)
                }

                true
            }

            player.prepareAsync()

        } catch (e: Exception) {

            stopMediaPlayer()

            if (!destroyed) {
                showMessage(
                    "تعذر تشغيل صوت جارفيس: ${e.message ?: "خطأ غير معروف"}"
                )
                speakFallback(fallbackText)
            }
        }
    }

    // ==============================
    // FINISH SPEAKING
    // ==============================

    private fun finishSpeaking() {

        if (destroyed) return

        speechInProgress = false

        jarvisState = JarvisState.WAITING

        scheduleListening(800L)
    }

    // ==============================
    // STOP AUDIO
    // ==============================

    private fun stopMediaPlayer() {

        try {
            mediaPlayer?.setOnCompletionListener(null)
            mediaPlayer?.setOnErrorListener(null)
            mediaPlayer?.setOnPreparedListener(null)
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }

        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }

        mediaPlayer = null
    }

    // ==============================
    // INTERRUPT JARVIS
    // ==============================

    private fun interruptJarvis() {

        if (destroyed) return

        handler.removeCallbacks(listenRunnable)

        speechInProgress = false

        stopMediaPlayer()

        try {
            textToSpeech?.stop()
        } catch (_: Exception) {
        }

        jarvisState = JarvisState.WAITING

        scheduleListening(400L)
    }

    // ==============================
    // USER MESSAGES
    // ==============================

    private fun showMessage(message: String) {

        if (destroyed || message.isBlank()) return

        Toast.makeText(
            applicationContext,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    // ==============================
    // ACTIVITY CLEANUP
    // ==============================

    override fun onDestroy() {

        destroyed = true

        handler.removeCallbacksAndMessages(null)

        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
        }

        activeConnection = null

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        stopMediaPlayer()

        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {
        }

        textToSpeech = null

        networkExecutor.shutdownNow()

        super.onDestroy()
    }
}

/* =================================
   JARVIS CINEMATIC INTRO
================================= */

@Composable
fun JarvisApp(
    state: JarvisState,
    onIntroFinished: () -> Unit,
    onInterrupt: () -> Unit
) {

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
        delay(1700)
        introComplete = true

        delay(1900)
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
                .offset(
                    y = screenHeight * (0.39f * (1f - titleProgress))
                )
                .graphicsLayer {
                    scaleX = 1f - (0.48f * titleProgress)
                    scaleY = 1f - (0.48f * titleProgress)
                    alpha = 1f - (0.15f * titleProgress)
                },
            style = TextStyle(
                color = Color(0xFFDCEBFF),
                fontSize = 48.sp,
                letterSpacing = 9.sp,
                fontWeight = FontWeight.Light
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

/* =================================
   NANO PARTICLE ORB
================================= */

private data class NanoParticle(
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

            val y =
                1f - (index.toFloat() / (particleCount - 1)) * 2f

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
                easing = LinearEasing
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

    val rotation =
        if (isSpeaking) animatedPhase * 0.22f else 0f

    val pulse =
        if (isSpeaking) speakingPulse else 1f

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

        val projected = ArrayList<androidx.compose.ui.geometry.Offset>(
            particleCount
        )

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

            projected.add(
                androidx.compose.ui.geometry.Offset(x, y)
            )
        }

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0x332E8FE8),
                    Color(0x183B9EEB),
                    Color.Transparent
                ),
                center = androidx.compose.ui.geometry.Offset(
                    centerX,
                    centerY
                ),
                radius = orbRadius * 1.55f
            ),
            radius = orbRadius * 1.55f
        )

        for (i in particles.indices) {

            val first = projected[i]

            val neighbors = listOf(
                (i + 1) % particleCount,
                (i + 7) % particleCount
            )

            neighbors.forEach { next ->

                val second = projected[next]

                drawLine(
                    color = blue.copy(
                        alpha = 0.20f * formation
                    ),
                    start = first,
                    end = second,
                    strokeWidth = 0.65f
                )
            }
        }

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

        drawCircle(
            color = silver.copy(alpha = 0.28f * formation),
            radius = orbRadius * pulse,
            center = androidx.compose.ui.geometry.Offset(
                centerX,
                centerY
            ),
            style = Stroke(width = 1.1f)
        )

        drawCircle(
            color = blue.copy(alpha = 0.14f * formation),
            radius = orbRadius * 1.12f,
            center = androidx.compose.ui.geometry.Offset(
                centerX,
                centerY
            ),
            style = Stroke(width = 0.7f)
        )
    }
}
