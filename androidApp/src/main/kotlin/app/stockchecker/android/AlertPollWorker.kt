package app.stockchecker.android

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.stockchecker.domain.AlertTracker
import app.stockchecker.model.Alert

/**
 * Background poll (every ~15 min, WorkManager minimum) that turns new server alerts into local
 * notifications. A stand-in for real push (FCM) on Android.
 */
class AlertPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as StockCheckerApp
        if (!app.api.hasSession()) return Result.success()
        val alerts = runCatching { app.api.alerts() }.getOrElse { return Result.retry() }
        notifyNew(applicationContext, alerts)
        return Result.success()
    }

    companion object {
        private const val PREF_LAST_SEEN = "lastSeenAlertId"

        /** Shows notifications for alerts newer than the last one seen; the first run only records a baseline. */
        fun notifyNew(context: Context, alerts: List<Alert>) {
            val prefs = context.getSharedPreferences("stockchecker", Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(PREF_LAST_SEEN, -1)
            // An empty list still sets a baseline of 0, so the very first alert after sign-in is shown.
            val newest = alerts.maxOfOrNull { it.id } ?: 0L
            if (lastSeen >= 0) {
                AlertTracker.newSince(alerts, lastSeen).forEach { show(context, it) }
            }
            if (newest > lastSeen) prefs.edit().putLong(PREF_LAST_SEEN, newest).apply()
        }

        private fun show(context: Context, alert: Alert) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val open = PendingIntent.getActivity(
                context, alert.id.toInt(),
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, StockCheckerApp.CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(alert.id.toInt(), notification)
        }
    }
}
