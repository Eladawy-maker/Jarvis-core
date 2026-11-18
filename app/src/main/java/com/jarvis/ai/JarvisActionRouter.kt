
package com.jarvis.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings

object JarvisActionRouter {

    fun handle(context: Context, spokenText: String): String? {
        val command = spokenText.trim().lowercase()

        val intent = when {
            command.contains("افتح الكاميرا") ||
                    command == "كاميرا" ->
                Intent("android.media.action.IMAGE_CAPTURE")

            command.contains("افتح الإعدادات") ||
                    command.contains("افتح الضبط") ->
                Intent(Settings.ACTION_SETTINGS)

            command.contains("افتح الواي فاي") ||
                    command.contains("إعدادات الواي فاي") ->
                Intent(Settings.ACTION_WIFI_SETTINGS)

            command.contains("افتح البلوتوث") ||
                    command.contains("إعدادات البلوتوث") ->
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS)

            command.contains("افتح المنبه") ||
                    command.contains("افتح الساعة") ->
                Intent(AlarmClock.ACTION_SHOW_ALARMS)

            command.contains("افتح الخرائط") ||
                    command.contains("افتح جوجل مابس") ->
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))

            command.contains("افتح يوتيوب") ->
                openAppOrWebsite(
                    context,
                    "com.google.android.youtube",
                    "https://www.youtube.com"
                )

            command.contains("افتح واتساب") ->
                openAppOrWebsite(
                    context,
                    "com.whatsapp",
                    "https://www.whatsapp.com"
                )

            command.startsWith("ابحث عن ") -> {
                val query = spokenText.substringAfter("ابحث عن ").trim()
                if (query.isBlank()) return null

                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra("query", query)
                }
            }

            command.startsWith("اتصل ب ") ||
                    command.startsWith("اتصل على ") -> {
                val number = spokenText
                    .substringAfter("اتصل ", "")
                    .removePrefix("ب ")
                    .removePrefix("على ")
                    .trim()

                if (number.isBlank()) return null

                Intent(
                    Intent.ACTION_DIAL,
                    Uri.parse("tel:$number")
                )
            }

            command.contains("افتح المتصفح") ||
                    command.contains("افتح الإنترنت") ->
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com")
                )

            else -> return null
        }

        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            when {
                command.startsWith("اتصل") ->
                    "فتحت شاشة الاتصال. راجع الرقم واضغط اتصال بنفسك."

                command.startsWith("ابحث عن") ->
                    "فتحت البحث عن طلبك."

                else ->
                    "حاضر، نفذت أمر الفتح."
            }
        } catch (e: Exception) {
            "مش قادر أفتح المطلوب على الجهاز."
        }
    }

    private fun openAppOrWebsite(
        context: Context,
        packageName: String,
        fallbackUrl: String
    ): Intent {
        val appIntent = context.packageManager
            .getLaunchIntentForPackage(packageName)

        return appIntent ?: Intent(
            Intent.ACTION_VIEW,
            Uri.parse(fallbackUrl)
        )
    }
}
