package app.stockchecker.data

import app.stockchecker.model.Alert
import app.stockchecker.model.ApiError
import app.stockchecker.model.AuthResponse
import app.stockchecker.model.ConfigResponse
import app.stockchecker.model.CreateWatchRequest
import app.stockchecker.model.PushSubscriptionRequest
import app.stockchecker.model.PushUnsubscribeRequest
import app.stockchecker.model.RequestCodeRequest
import app.stockchecker.model.RequestCodeResponse
import app.stockchecker.model.UpdateWatchRequest
import app.stockchecker.model.User
import app.stockchecker.model.VerifyCodeRequest
import app.stockchecker.model.Watch
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * HTTP client for the Stock Checker server. Implements every repository interface so clients can
 * depend on the narrow interfaces (and tests can swap in fakes).
 *
 * @param baseUrl e.g. "http://10.0.2.2:8080" on the Android emulator, or "" in the browser (same origin).
 */
class StockCheckerApi(
    baseUrl: String,
    private val tokenStore: TokenStore,
    engine: HttpClientEngine? = null,
) : AuthRepository, WatchRepository, AlertRepository, PushRepository {

    private val base = baseUrl.trimEnd('/')

    private val client: HttpClient = run {
        val config: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = false
            install(ContentNegotiation) { json(ApiJson) }
            defaultRequest { contentType(ContentType.Application.Json) }
        }
        if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    private fun url(path: String) = "$base$path"

    private fun HttpRequestBuilder.auth() {
        tokenStore.token?.let { bearerAuth(it) }
    }

    private suspend fun HttpResponse.ensureOk(): HttpResponse {
        if (status.isSuccess()) return this
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val message = runCatching { ApiJson.decodeFromString(ApiError.serializer(), text).error }.getOrNull()
            ?: text.ifBlank { status.description }
        if (status.value == 401) tokenStore.token = null
        throw ApiException(status.value, message)
    }

    override fun hasSession(): Boolean = tokenStore.token != null

    override suspend fun config(): ConfigResponse = client.get(url("/api/config")).ensureOk().body()

    override suspend fun requestCode(email: String): RequestCodeResponse =
        client.post(url("/api/auth/request-code")) { setBody(RequestCodeRequest(email)) }.ensureOk().body()

    override suspend fun verifyCode(email: String, code: String): User {
        val auth: AuthResponse = client.post(url("/api/auth/verify")) { setBody(VerifyCodeRequest(email, code)) }.ensureOk().body()
        tokenStore.token = auth.token
        return auth.user
    }

    override suspend fun me(): User = client.get(url("/api/me")) { auth() }.ensureOk().body()

    override suspend fun signOut() {
        try {
            client.post(url("/api/auth/logout")) { auth() }
        } finally {
            tokenStore.token = null
        }
    }

    override suspend fun watches(): List<Watch> = client.get(url("/api/watches")) { auth() }.ensureOk().body()

    override suspend fun addWatch(request: CreateWatchRequest): Watch =
        client.post(url("/api/watches")) { auth(); setBody(request) }.ensureOk().body()

    override suspend fun setActive(id: Long, active: Boolean): Watch =
        client.patch(url("/api/watches/$id")) { auth(); setBody(UpdateWatchRequest(active)) }.ensureOk().body()

    override suspend fun deleteWatch(id: Long) {
        client.delete(url("/api/watches/$id")) { auth() }.ensureOk()
    }

    override suspend fun checkNow(id: Long): Watch = client.post(url("/api/watches/$id/check")) { auth() }.ensureOk().body()

    override suspend fun alerts(): List<Alert> = client.get(url("/api/alerts")) { auth() }.ensureOk().body()

    override suspend fun subscribe(subscription: PushSubscriptionRequest) {
        client.post(url("/api/push/subscribe")) { auth(); setBody(subscription) }.ensureOk()
    }

    override suspend fun unsubscribe(endpoint: String) {
        client.post(url("/api/push/unsubscribe")) { auth(); setBody(PushUnsubscribeRequest(endpoint)) }.ensureOk()
    }

    override suspend fun sendTest() {
        client.post(url("/api/push/test")) { auth() }.ensureOk()
    }
}
