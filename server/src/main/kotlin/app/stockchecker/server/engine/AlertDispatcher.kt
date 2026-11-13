package app.stockchecker.server.engine

import app.stockchecker.data.ApiJson
import app.stockchecker.model.Alert
import app.stockchecker.model.DeclarativeNotification
import app.stockchecker.model.DeclarativePushMessage
import app.stockchecker.model.PushData
import app.stockchecker.model.StockStatus
import app.stockchecker.server.auth.Crypto
import app.stockchecker.server.db.Repository
import app.stockchecker.server.mail.Mailer
import app.stockchecker.server.push.PushOutcome
import app.stockchecker.server.push.PushSender
import org.slf4j.LoggerFactory

data class DispatchReport(val alert: Alert, val pushDelivered: Int, val pushExpired: Int, val emailed: Boolean)

/** Turns a stock change into an alert row, Web Push messages, and (if needed) an email. */
class AlertDispatcher(
    private val repo: Repository,
    private val push: PushSender,
    private val mailer: Mailer,
    private val publicBaseUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AlertDispatcher::class.java)

    suspend fun dispatch(
        userId: Long,
        watchId: Long,
        status: StockStatus,
        title: String,
        body: String,
        productUrl: String?,
        storeName: String?,
    ): DispatchReport {
        val receipt = Crypto.randomToken(16)
        val created = repo.createAlert(userId, watchId, status, title, body, productUrl, storeName, Crypto.sha256Hex(receipt), now())
        return deliver(userId, created, receipt)
    }

    /** Sends a test notification to all of a user's devices. */
    suspend fun sendTest(userId: Long): Int {
        val payload = payload(0, "", "Test notification", "Push alerts are working on this device.")
        var delivered = 0
        for (sub in repo.subscriptions(userId)) {
            if (push.send(sub, payload, ttlSeconds = 300) == PushOutcome.DELIVERED) delivered++
        }
        return delivered
    }

    private suspend fun deliver(userId: Long, alert: Alert, receipt: String): DispatchReport {
        val payload = payload(alert.id, receipt, alert.title, alert.body)
        var delivered = 0
        var expired = 0
        if (push.enabled) {
            for (sub in repo.subscriptions(userId)) {
                when (push.send(sub, payload)) {
                    PushOutcome.DELIVERED -> { delivered++; repo.markSubscriptionResult(sub.id, true, now()) }
                    PushOutcome.EXPIRED -> { expired++; repo.deleteSubscription(sub.endpoint) }
                    PushOutcome.FAILED -> repo.markSubscriptionResult(sub.id, false, now())
                }
            }
        }
        var emailed = false
        if (delivered > 0) {
            repo.markPushSent(alert.id, now())
        } else {
            // No device could be reached: email immediately.
            emailed = email(userId, alert)
        }
        log.info("Alert {} '{}': push delivered={} expired={} emailed={}", alert.id, alert.title, delivered, expired, emailed)
        return DispatchReport(alert, delivered, expired, emailed)
    }

    /** Emails alerts that were pushed but never confirmed as shown (see /api/alert-shown). */
    suspend fun emailUnconfirmed(olderThanMs: Long): Int {
        var count = 0
        for (candidate in repo.alertsNeedingEmailFallback(now() - olderThanMs)) {
            if (sendEmail(candidate.email, candidate.alert)) {
                repo.markEmailed(candidate.alert.id, now())
                count++
            }
        }
        return count
    }

    private suspend fun email(userId: Long, alert: Alert): Boolean {
        val to = repo.userEmail(userId) ?: return false
        val ok = sendEmail(to, alert)
        if (ok) repo.markEmailed(alert.id, now())
        return ok
    }

    private suspend fun sendEmail(to: String, alert: Alert): Boolean = try {
        mailer.send(
            to = to,
            subject = alert.title,
            text = buildString {
                appendLine(alert.body)
                alert.url?.let { appendLine(); appendLine("Product: $it") }
                appendLine()
                appendLine("Manage your watches: $publicBaseUrl/")
            },
        )
        true
    } catch (e: Exception) {
        log.warn("Email to {} failed: {}", to, e.message)
        false
    }

    private fun payload(alertId: Long, receipt: String, title: String, body: String): String {
        val navigate = if (alertId > 0) "$publicBaseUrl/?alert=$alertId" else "$publicBaseUrl/"
        return ApiJson.encodeToString(
            DeclarativePushMessage.serializer(),
            DeclarativePushMessage(
                notification = DeclarativeNotification(
                    title = title,
                    body = body,
                    navigate = navigate,
                    tag = if (alertId > 0) "alert-$alertId" else "test",
                    data = if (alertId > 0) PushData(alertId, receipt) else null,
                ),
            ),
        )
    }
}
