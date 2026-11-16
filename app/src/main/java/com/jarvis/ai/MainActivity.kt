
package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.delay
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            JarvisApp()
        }
    }
}

@Composable
fun JarvisApp() {
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
                        .height(340.dp)
                )

                Spacer(modifier = Modifier.height(35.dp))

                Text(
                    text = "J.A.R.V.I.S ONLINE",
                    color = gold,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "SYSTEM READY",
                    color = Color(0xFF8A704F),
                    fontSize = 11.sp,
                    letterSpacing = 2.sp
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
fun JarvisOrb(modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            angle = (angle + 1.2f) % 360f
            delay(16)
        }
    }

    Canvas(modifier = modifier) {
        val center = Offset(
            x = size.width / 2,
            y = size.height / 2
        )

        val radius = minOf(size.width, size.height) * 0.29f

        val gold = Color(0xFFFFB547)
        val orange = Color(0xFFFF7417)
        val paleGold = Color(0xFFFFE0A0)

        // الهالة الخارجية
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

        // المدارات الخارجية المتحركة
        for (i in 0 until 5) {
            val rotation = angle *
                (if (i % 2 == 0) 1f else -0.65f) + i * 36f

            val ovalWidth = radius * (1.35f + i * 0.16f)
            val ovalHeight = radius * (0.48f + i * 0.09f)

            withTransform({
                rotate(rotation, center)
            }) {
                drawOval(
                    color = gold.copy(alpha = 0.65f - i * 0.07f),
                    topLeft = Offset(
                        center.x - ovalWidth,
                        center.y - ovalHeight
                    ),
                    size = Size(
                        ovalWidth * 2,
                        ovalHeight * 2
                    ),
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // حلقات الكرة المتقاطعة
        for (i in 0 until 8) {
            val rotation = angle *
                (if (i % 2 == 0) 0.8f else -1.1f) + i * 22.5f

            withTransform({
                rotate(rotation, center)
            }) {
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

        // نقاط البيانات المتحركة
        for (i in 0 until 36) {
            val orbit = radius * (0.65f + (i % 5) * 0.19f)

            val radians = Math.toRadians(
                (
                    angle * (if (i % 2 == 0) 1.5f else -1f) +
                        i * 10f
                    ).toDouble()
            )

            val x = center.x + cos(radians).toFloat() * orbit
            val y = center.y + sin(radians).toFloat() * orbit * 0.65f

            drawCircle(
                color = if (i % 3 == 0) paleGold else orange,
                radius = if (i % 3 == 0) 3.dp.toPx() else 1.5.dp.toPx(),
                center = Offset(x, y)
            )
        }

        // القلب المركزي المتوهج
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

        // مركز الطاقة
        drawCircle(
            color = Color.White.copy(alpha = 0.9f),
            radius = radius * 0.12f,
            center = center
        )

        // نقاط مضيئة حول القلب
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
