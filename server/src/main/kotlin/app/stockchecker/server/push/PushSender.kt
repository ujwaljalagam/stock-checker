package app.stockchecker.server.push

import app.stockchecker.server.AppConfig
import app.stockchecker.server.db.StoredSubscription
import com.interaso.webpush.VapidKeys
import com.interaso.webpush.WebPush
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import org.slf4j.LoggerFactory
import java.util.Base64

enum class PushOutcome {
    /** Accepted by the push service (201/202). */
    DELIVERED,

    /** Subscription is gone (404/410): delete it. */
    EXPIRED,

    /** Transient or unknown failure. */
    FAILED,
}

interface PushSender {
    val enabled: Boolean
    val publicKey: String?
    suspend fun send(subscription: StoredSubscription, payloadJson: String, ttlSeconds: Int = 3600): PushOutcome
}

object DisabledPushSender : PushSender {
    override val enabled = false
    override val publicKey: String? = null
    override suspend fun send(subscription: StoredSubscription, payloadJson: String, ttlSeconds: Int) = PushOutcome.FAILED
}

/**
 * Real Web Push: RFC 8291 aes128gcm payload encryption + RFC 8292 VAPID JWT, built with
 * com.interaso:webpush and posted with a non-blocking Ktor client.
 *
 * Keys are the URL-safe base64 "uncompressed bytes" form (same as the `web-push` npm package), so the
 * public key can be handed straight to `PushManager.subscribe({ applicationServerKey })`.
 */
class VapidPushSender(
    config: AppConfig.VapidConfig,
    private val http: HttpClient = HttpClient(CIO),
) : PushSender {
    private val log = LoggerFactory.getLogger(VapidPushSender::class.java)
    private val webPush = WebPush(config.subject, VapidKeys.fromUncompressedBytes(config.publicKey, config.privateKey))
    override val enabled = true
    override val publicKey: String = config.publicKey

    override suspend fun send(subscription: StoredSubscription, payloadJson: String, ttlSeconds: Int): PushOutcome {
        return try {
            val body = webPush.getBody(payloadJson.toByteArray(), decode(subscription.p256dh), decode(subscription.auth))
            val headers = webPush.getHeaders(subscription.endpoint, ttlSeconds, null, WebPush.Urgency.High)
            val response = http.post(subscription.endpoint) {
                headers { headers.forEach { (k, v) -> append(k, v) } }
                setBody(body)
            }
            when (val code = response.status.value) {
                in 200..299 -> PushOutcome.DELIVERED
                404, 410 -> PushOutcome.EXPIRED
                else -> {
                    log.warn("Push to {} failed: {} {}", host(subscription.endpoint), code, response.bodyAsText().take(200))
                    PushOutcome.FAILED
                }
            }
        } catch (e: Exception) {
            log.warn("Push to {} errored: {}", host(subscription.endpoint), e.message)
            PushOutcome.FAILED
        }
    }

    private fun host(endpoint: String) = runCatching { java.net.URI(endpoint).host }.getOrDefault("?")

    companion object {
        fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value.trim().replace('+', '-').replace('/', '_').trimEnd('='))
    }
}
