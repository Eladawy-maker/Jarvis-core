package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider

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
    val backgroundColor = Color(0xFF07111F)
    val cyanColor = Color(0xFF00D9FF)

    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("جاهز للعمل") }

    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Rtl
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = cyanColor,
                background = backgroundColor,
                surface = Color(0xFF102235)
            )
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = backgroundColor
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "J.A.R.V.I.S",
                        color = cyanColor,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "نظام المساعدة الذكي",
                        color = Color.White,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(40.dp))

                    Text(
                        text = "◉",
                        color = cyanColor,
                        fontSize = 90.sp
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Text(
                        text = status,
                        color = cyanColor,
                        fontSize = 18.sp
                    )

                    Spacer(modifier = Modifier.height(35.dp))

                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text("اكتب رسالة لجارفيس")
                        },
                        placeholder = {
                            Text("أنا جاهز للاستماع...")
                        },
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            status = if (message.isNotBlank()) {
                                "استلمت رسالتك: $message"
                            } else {
                                "اكتب رسالة الأول"
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cyanColor,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "إرسال",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(30.dp))

                    Text(
                        text = "JARVIS CORE • VERSION 1.0",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
