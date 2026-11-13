package app.stockchecker.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.stockchecker.data.StockCheckerApi
import app.stockchecker.data.TokenStore
import java.util.concurrent.TimeUnit

class PrefsTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("stockchecker", Context.MODE_PRIVATE)
    override var token: String?
        get() = prefs.getString("token", null)
        set(value) = prefs.edit().apply { if (value == null) remove("token") else putString("token", value) }.apply()
}

class StockCheckerApp : Application() {
    lateinit var api: StockCheckerApi
        private set

    override fun onCreate() {
        super.onCreate()
        api = StockCheckerApi(BuildConfig.SERVER_URL, PrefsTokenStore(this))
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Stock alerts", NotificationManager.IMPORTANCE_HIGH),
        )
        // Native apps can't receive Web Push. Until FCM is added, poll for new alerts in the background.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "alert-poll",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AlertPollWorker>(15, TimeUnit.MINUTES).build(),
        )
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
    }
}
