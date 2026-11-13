package app.stockchecker.server.routes

import app.stockchecker.data.ApiJson
import app.stockchecker.domain.Validation
import app.stockchecker.model.AlertShownRequest
import app.stockchecker.model.ApiError
import app.stockchecker.model.AuthResponse
import app.stockchecker.model.ConfigResponse
import app.stockchecker.model.CreateWatchRequest
import app.stockchecker.model.PushResubscribeRequest
import app.stockchecker.model.PushStatusResponse
import app.stockchecker.model.PushSubscriptionRequest
import app.stockchecker.model.PushUnsubscribeRequest
import app.stockchecker.model.RequestCodeRequest
import app.stockchecker.model.RequestCodeResponse
import app.stockchecker.model.UpdateWatchRequest
import app.stockchecker.model.User
import app.stockchecker.model.VerifyCodeRequest
import app.stockchecker.server.Services
import app.stockchecker.server.auth.AuthResult
import app.stockchecker.server.auth.Crypto
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.http.content.staticFiles
import io.ktor.server.plugins.BadRequestException
import org.slf4j.event.Level
import java.io.File

private const val MAX_WATCHES_PER_USER = 50

private suspend fun ApplicationCall.fail(status: HttpStatusCode, message: String) = respond(status, ApiError(message))

private val ApplicationCall.user: User get() = principal<User>()!!

private fun ApplicationCall.bearerToken(): String? =
    request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

fun Application.configureHttp(services: Services) {
    val config = services.config

    install(DefaultHeaders) { header("X-Content-Type-Options", "nosniff") }
    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api") }
    }
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) {
        exception<BadRequestException> { call, cause -> call.fail(HttpStatusCode.BadRequest, cause.message ?: "Bad request") }
        exception<IllegalArgumentException> { call, cause -> call.fail(HttpStatusCode.BadRequest, cause.message ?: "Bad request") }
        exception<IllegalStateException> { call, cause -> call.fail(HttpStatusCode.TooManyRequests, cause.message ?: "Try again later") }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled error", cause)
            call.fail(HttpStatusCode.InternalServerError, "Something went wrong")
        }
    }
    if (config.corsHosts.isNotEmpty()) {
        install(CORS) {
            config.corsHosts.forEach { allowHost(it.substringAfter("://"), schemes = listOf(it.substringBefore("://", "http"))) }
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
        }
    }
    install(Authentication) {
        bearer("session") {
            authenticate { credential -> services.auth.userForToken(credential.token) }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }

        route("/api") {
            get("/config") {
                call.respond(
                    ConfigResponse(
                        vapidPublicKey = services.push.publicKey,
                        retailers = services.checkers.map { it.retailer },
                        emailFallback = true,
                    ),
                )
            }

            authRoutes(services)
            pushPublicRoutes(services)

            authenticate("session") {
                get("/me") { call.respond(call.user) }
                watchRoutes(services)
                get("/alerts") { call.respond(services.repo.alerts(call.user.id)) }
                pushRoutes(services)
            }
        }

        webApp(services)
    }
}

private fun Route.authRoutes(services: Services) = route("/auth") {
    post("/request-code") {
        val req = call.receive<RequestCodeRequest>()
        val devCode = services.auth.requestCode(req.email)
        call.respond(RequestCodeResponse(sent = true, devCode = devCode))
    }
    post("/verify") {
        val req = call.receive<VerifyCodeRequest>()
        when (val result = services.auth.verify(req.email, req.code)) {
            is AuthResult.Success -> call.respond(AuthResponse(result.token, result.user))
            is AuthResult.Failure -> call.fail(HttpStatusCode.fromValue(result.status), result.message)
        }
    }
    post("/logout") {
        call.bearerToken()?.let { services.auth.logout(it) }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.watchRoutes(services: Services) = route("/watches") {
    get { call.respond(services.repo.watches(call.user.id)) }

    post {
        val req = call.receive<CreateWatchRequest>().let { it.copy(sku = it.sku.trim(), zip = it.zip.trim(), productName = it.productName?.trim()?.ifEmpty { null }) }
        val checkers = services.checkers.associateBy { it.retailer.id }
        val problems = Validation.validateWatch(req, checkers.keys)
        if (problems.isNotEmpty()) return@post call.fail(HttpStatusCode.BadRequest, problems.joinToString(" "))
        if (services.repo.countWatches(call.user.id) >= MAX_WATCHES_PER_USER) {
            return@post call.fail(HttpStatusCode.BadRequest, "You can watch up to $MAX_WATCHES_PER_USER products.")
        }
        val name = req.productName ?: runCatching { checkers.getValue(req.retailer).lookupName(req.sku) }.getOrNull()
        val watch = services.repo.createWatch(call.user.id, req.retailer, req.sku, name, null, req.zip, req.radiusMiles, System.currentTimeMillis())
        call.respond(HttpStatusCode.Created, watch)
    }

    patch("/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@patch call.fail(HttpStatusCode.BadRequest, "Bad id")
        val req = call.receive<UpdateWatchRequest>()
        if (!services.repo.setWatchActive(call.user.id, id, req.active)) return@patch call.fail(HttpStatusCode.NotFound, "Watch not found")
        call.respond(services.repo.watch(call.user.id, id)!!.watch)
    }

    delete("/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@delete call.fail(HttpStatusCode.BadRequest, "Bad id")
        if (!services.repo.deleteWatch(call.user.id, id)) return@delete call.fail(HttpStatusCode.NotFound, "Watch not found")
        call.respond(HttpStatusCode.NoContent)
    }

    post("/{id}/check") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@post call.fail(HttpStatusCode.BadRequest, "Bad id")
        val stored = services.repo.watch(call.user.id, id) ?: return@post call.fail(HttpStatusCode.NotFound, "Watch not found")
        call.respond(services.engine.checkNow(stored))
    }
}

private fun Route.pushRoutes(services: Services) = route("/push") {
    post("/subscribe") {
        val sub = call.receive<PushSubscriptionRequest>()
        require(sub.endpoint.startsWith("https://") || sub.endpoint.startsWith("http://localhost") || sub.endpoint.startsWith("http://127.0.0.1")) {
            "Invalid push endpoint"
        }
        require(sub.keys.p256dh.isNotBlank() && sub.keys.auth.isNotBlank()) { "Missing subscription keys" }
        services.repo.upsertSubscription(
            call.user.id, sub.endpoint, sub.keys.p256dh, sub.keys.auth,
            sub.userAgent ?: call.request.header(HttpHeaders.UserAgent), System.currentTimeMillis(),
        )
        call.respond(PushStatusResponse(services.repo.subscriptions(call.user.id).size))
    }
    post("/unsubscribe") {
        val req = call.receive<PushUnsubscribeRequest>()
        services.repo.deleteSubscription(req.endpoint, call.user.id)
        call.respond(PushStatusResponse(services.repo.subscriptions(call.user.id).size))
    }
    get("/status") { call.respond(PushStatusResponse(services.repo.subscriptions(call.user.id).size)) }
    post("/test") {
        if (!services.push.enabled) return@post call.fail(HttpStatusCode.ServiceUnavailable, "Web Push is not configured on the server")
        val delivered = services.dispatcher.sendTest(call.user.id)
        call.respond(PushStatusResponse(delivered))
    }
}

/** Endpoints the service worker calls without a session token. */
private fun Route.pushPublicRoutes(services: Services) {
    // pushsubscriptionchange: the browser rotated the subscription; trust the old endpoint as proof of ownership.
    post("/push/resubscribe") {
        val req = call.receive<PushResubscribeRequest>()
        val owner = services.repo.subscriptionOwner(req.oldEndpoint) ?: return@post call.fail(HttpStatusCode.NotFound, "Unknown subscription")
        services.repo.deleteSubscription(req.oldEndpoint)
        val sub = req.subscription
        services.repo.upsertSubscription(owner, sub.endpoint, sub.keys.p256dh, sub.keys.auth, sub.userAgent, System.currentTimeMillis())
        call.respond(HttpStatusCode.NoContent)
    }

    // Delivery receipt: the notification is actually on screen.
    post("/alert-shown") {
        val req = call.receive<AlertShownRequest>()
        val ok = services.repo.markShown(req.alertId, Crypto.sha256Hex(req.receipt), System.currentTimeMillis())
        if (ok) call.respond(HttpStatusCode.NoContent) else call.fail(HttpStatusCode.NotFound, "Unknown alert")
    }
}

/** Serves the built Kotlin/Wasm dashboard (and sw.js at the site root so it can control "/"). */
private fun Route.webApp(services: Services) {
    val dir = File(services.config.webStaticDir ?: "webApp/build/dist/wasmJs/productionExecutable")
    if (!dir.isDirectory) {
        get("/") {
            call.respondText(
                "Web app not built. Run ./gradlew :webApp:wasmJsBrowserDistribution (looked in ${dir.absolutePath}).",
                ContentType.Text.Plain,
            )
        }
        return
    }
    get("/sw.js") {
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("Service-Worker-Allowed", "/")
        call.respondFile(File(dir, "sw.js"))
    }
    staticFiles("/", dir) {
        default("index.html")
    }
}
