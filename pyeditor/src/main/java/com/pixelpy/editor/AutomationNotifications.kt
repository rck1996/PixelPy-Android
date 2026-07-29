package com.pixelpy.editor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal object AutomationNotifications {
    private const val CHANNEL_ID = "automation-results"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Resultados de automatizaciones",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "Avisa cuando un script programado termina." },
            )
        }
    }

    fun show(context: Context, automation: ScriptAutomation) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val target = if (automation.publishedArtifactPath != null) {
            Intent(context, PublishedResultActivity::class.java).putExtra(EXTRA_AUTOMATION_ID, automation.id)
        } else {
            Intent(context, MainActivity::class.java).putExtra(EXTRA_AUTOMATION_ID, automation.id)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            automation.id.hashCode(),
            target,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val successful = automation.lastStatus == AutomationRunStatus.Success
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.pixelpy_brand_mark)
            .setContentTitle(if (successful) "${automation.name} terminó" else "${automation.name} necesita atención")
            .setContentText(automation.summary.lineSequence().firstOrNull().orEmpty().ifBlank { automation.lastStatus.name })
            .setStyle(NotificationCompat.BigTextStyle().bigText(automation.summary))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .addAction(0, if (automation.publishedArtifactPath != null) "VER RESULTADO" else "VER DETALLE", pending)
            .build()
        NotificationManagerCompat.from(context).notify(automation.id.hashCode(), notification)
    }
}
