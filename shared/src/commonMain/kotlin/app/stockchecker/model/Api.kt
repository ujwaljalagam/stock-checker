package app.stockchecker.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---- Request / response contracts shared by the server and every client ----

@Serializable
data class ConfigResponse(
    @SerialName("vapid_public_key") val vapidPublicKey: String?,
    val retailers: List<Retailer>,
    @SerialName("email_fallback") val emailFallback: Boolean,
)

@Serializable
data class RequestCodeRequest(val email: String)

@Serializable
data class RequestCodeResponse(
    val sent: Boolean,
    /** Only populated when the server runs in dev mode (codes are also logged to the console). */
    val devCode: String? = null,
)

@Serializable
data class VerifyCodeRequest(val email: String, val code: String)

@Serializable
data class AuthResponse(val token: String, val user: User)

@Serializable
data class CreateWatchRequest(
    val retailer: String,
    val sku: String,
    val zip: String,
    val radiusMiles: Int = 25,
    val productName: String? = null,
)

@Serializable
data class UpdateWatchRequest(val active: Boolean)

@Serializable
data class PushKeys(val p256dh: String, val auth: String)

/** Mirrors the JSON produced by `PushSubscription.toJSON()` in the browser. */
@Serializable
data class PushSubscriptionRequest(
    val endpoint: String,
    val keys: PushKeys,
    val expirationTime: Long? = null,
    val userAgent: String? = null,
)

/** Sent by the service worker on `pushsubscriptionchange` (no session token available there). */
@Serializable
data class PushResubscribeRequest(
    val oldEndpoint: String,
    val subscription: PushSubscriptionRequest,
)

@Serializable
data class PushUnsubscribeRequest(val endpoint: String)

@Serializable
data class PushStatusResponse(val subscriptions: Int)

/** Delivery receipt posted by the service worker once a notification is on screen. */
@Serializable
data class AlertShownRequest(val alertId: Long, val receipt: String)

@Serializable
data class ApiError(val error: String)

// ---- Web Push payload (Declarative Web Push, Safari 18.4+, with a classic service-worker fallback) ----

@Serializable
data class DeclarativePushMessage(
    /** Magic number that marks the payload as Declarative Web Push. */
    @SerialName("web_push") val webPush: Int = 8030,
    val notification: DeclarativeNotification,
)

@Serializable
data class DeclarativeNotification(
    val title: String,
    val body: String,
    /** URL opened when the user taps the notification (required by the declarative format). */
    val navigate: String,
    val tag: String? = null,
    val silent: Boolean = false,
    /** Our extras; ignored by the declarative path, used by sw.js for receipts. */
    val data: PushData? = null,
)

@Serializable
data class PushData(val alertId: Long, val receipt: String)
