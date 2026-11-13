@file:OptIn(ExperimentalWasmJsInterop::class)

package app.stockchecker.web

import app.stockchecker.data.ApiJson
import app.stockchecker.data.StockCheckerApi
import app.stockchecker.data.TokenStore
import app.stockchecker.model.PushSubscriptionRequest
import app.stockchecker.model.StockStatus
import app.stockchecker.presentation.AppState
import app.stockchecker.presentation.AppStore
import app.stockchecker.presentation.Session
import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private class LocalStorageTokenStore : TokenStore {
    override var token: String?
        get() = localStorage.getItem("stockchecker.token")
        set(value) = if (value == null) localStorage.removeItem("stockchecker.token") else localStorage.setItem("stockchecker.token", value)
}

private fun el(id: String): HTMLElement = document.getElementById(id) as HTMLElement
private fun input(id: String) = document.getElementById(id) as HTMLInputElement
private fun HTMLElement.show(visible: Boolean) { hidden = !visible }

private fun esc(s: String?): String = (s ?: "")
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

private fun formatTime(ms: Long?): String = if (ms == null) "never" else scPush.formatTime(ms.toDouble())

private fun statusLabel(s: StockStatus) = when (s) {
    StockStatus.IN_STOCK -> "In stock"
    StockStatus.LIMITED -> "Low stock"
    StockStatus.OUT_OF_STOCK -> "Out of stock"
    StockStatus.UNKNOWN -> "Not checked yet"
}

private val scope = MainScope()
private val api = StockCheckerApi(window.location.origin, LocalStorageTokenStore())
private val store = AppStore(api, api, api, scope)
private var lastRetailerIds = ""

fun main() {
    wireForms()
    store.observe(::render)
    store.start()
    scope.launch { scPush.registerServiceWorker().await<JsString>() }
    scPush.onAlert { store.refresh() }
    // Keep the dashboard fresh while it is open.
    window.setInterval({ if (store.current.signedIn) store.refresh(); null }, 60_000)
}

private fun wireForms() {
    el("email-form").addEventListener("submit") { e -> e.preventDefault(); store.requestCode(input("email").value) }
    el("code-form").addEventListener("submit") { e -> e.preventDefault(); store.verifyCode(input("code-input").value) }
    el("change-email").addEventListener("click") { store.changeEmail() }
    el("sign-out").addEventListener("click") { store.signOut() }
    el("refresh").addEventListener("click") { store.refresh() }
    el("retailer").addEventListener("change") { updateSkuHint() }
    el("watch-form").addEventListener("submit") { e ->
        e.preventDefault()
        store.addWatch(
            retailer = (document.getElementById("retailer") as HTMLSelectElement).value,
            sku = input("sku").value,
            zip = input("zip").value,
            radiusMiles = input("radius").value.toIntOrNull() ?: 25,
            productName = input("product-name").value,
        )
        input("sku").value = ""
        input("product-name").value = ""
    }
    // Event delegation for per-watch buttons.
    el("watches").addEventListener("click") { e ->
        val target = e.target as? HTMLButtonElement ?: return@addEventListener
        val id = target.getAttribute("data-id")?.toLongOrNull() ?: return@addEventListener
        when (target.getAttribute("data-action")) {
            "toggle" -> store.toggleWatch(id)
            "check" -> store.checkNow(id)
            "delete" -> if (window.confirm("Stop watching this product?")) store.removeWatch(id)
        }
    }
    el("enable-push").addEventListener("click") { enablePush() }
    el("disable-push").addEventListener("click") { disablePush() }
    el("test-push").addEventListener("click") {
        scope.launch {
            runCatching { api.sendTest() }
                .onSuccess { setPushStatus("Test sent. It should appear in a few seconds.") }
                .onFailure { setPushStatus("Test failed: ${it.message}") }
        }
    }
}

private fun render(state: AppState) {
    el("loading").show(state.loading)
    el("error").apply { show(state.error != null); textContent = state.error }
    el("info").apply { show(state.info != null && state.error == null); textContent = state.info }

    val session = state.session
    el("signin").show(session is Session.SignedOut)
    el("code").show(session is Session.CodeSent)
    el("dashboard").show(session is Session.SignedIn)
    el("account").show(session is Session.SignedIn)
    el("account-email").textContent = state.userEmail ?: ""

    if (session is Session.CodeSent) {
        el("code-email").textContent = session.email
        el("dev-code").apply {
            show(session.devCode != null)
            textContent = "Dev mode code: ${session.devCode}"
        }
    }

    val retailerIds = state.retailers.joinToString { it.id }
    if (retailerIds != lastRetailerIds) {
        lastRetailerIds = retailerIds
        el("retailer").innerHTML = state.retailers.joinToString("") { "<option value=\"${esc(it.id)}\">${esc(it.name)}</option>" }
        updateSkuHint()
    }

    el("watches").innerHTML = if (state.watches.isEmpty()) {
        "<li class=\"meta\">No watches yet. Add one above.</li>"
    } else {
        state.watches.joinToString("") { w ->
            val retailerName = state.retailers.firstOrNull { it.id == w.product.retailer }?.name ?: w.product.retailer
            val where = w.lastStoreName?.takeIf { w.lastStatus.isAvailable }?.let { " · ${esc(it)}" } ?: ""
            val error = w.lastError?.let { "<div class=\"meta\" style=\"color:var(--err)\">${esc(it)}</div>" } ?: ""
            """
            <li class="${if (w.active) "" else "paused"}" data-watch="${w.id}">
              <div>
                <div><b>${esc(w.product.name ?: w.product.sku)}</b> <span class="badge ${w.lastStatus.name}">${statusLabel(w.lastStatus)}</span></div>
                <div class="meta">${esc(retailerName)} · SKU ${esc(w.product.sku)} · ${esc(w.zip)} (${w.radiusMiles} mi)$where</div>
                <div class="meta">Last checked ${formatTime(w.lastCheckedAt)}${if (w.active) "" else " · paused"}</div>
                $error
              </div>
              <div>
                <button class="secondary" data-action="check" data-id="${w.id}">Check now</button>
                <button class="link" data-action="toggle" data-id="${w.id}">${if (w.active) "Pause" else "Resume"}</button>
                <button class="danger" data-action="delete" data-id="${w.id}">Remove</button>
              </div>
            </li>
            """.trimIndent()
        }
    }

    el("alerts").innerHTML = if (state.alerts.isEmpty()) {
        "<li class=\"meta\">No alerts yet.</li>"
    } else {
        state.alerts.joinToString("") { a ->
            val delivery = buildList {
                if (a.pushSentAt != null) add(if (a.shownAt != null) "push shown ✓" else "push sent")
                if (a.emailedAt != null) add("emailed")
            }.joinToString(" · ").ifEmpty { "not delivered" }
            val link = a.url?.let { " · <a href=\"${esc(it)}\" target=\"_blank\" rel=\"noopener\">product</a>" } ?: ""
            """
            <li data-alert="${a.id}">
              <div><b>${esc(a.title)}</b><div>${esc(a.body)}</div>
              <div class="meta">${formatTime(a.createdAt)} · $delivery$link</div></div>
            </li>
            """.trimIndent()
        }
    }

    if (session is Session.SignedIn) refreshPushUi(state)
}

private fun updateSkuHint() {
    val id = (document.getElementById("retailer") as HTMLSelectElement).value
    el("sku-hint").textContent = store.current.retailers.firstOrNull { it.id == id }?.skuHint ?: ""
}

private fun setPushStatus(text: String) { el("push-status").textContent = text }

private var pushUiChecked = false

private fun refreshPushUi(state: AppState) {
    if (pushUiChecked) return
    pushUiChecked = true
    val support = scPush.support()
    el("ios-help").show(support == "needs-home-screen")
    when {
        state.vapidPublicKey == null -> { setPushStatus("Push alerts are not configured on the server; you'll get email alerts."); showPushButtons(false, false) }
        support == "needs-home-screen" -> { setPushStatus("Add this site to your Home Screen to get push alerts. Until then we'll email you."); showPushButtons(false, false) }
        support == "unsupported" -> { setPushStatus("This browser can't receive push alerts; we'll email you instead."); showPushButtons(false, false) }
        support == "denied" -> { setPushStatus("Notifications are blocked for this site. Allow them in your browser settings, or rely on email."); showPushButtons(false, false) }
        else -> scope.launch {
            val current = runCatching { scPush.current().await<JsString>().toString() }.getOrDefault("")
            if (current.isNotEmpty()) {
                // Re-send in case the server lost it (e.g. a new database).
                runCatching { api.subscribe(ApiJson.decodeFromString(PushSubscriptionRequest.serializer(), current)) }
                setPushStatus("Push alerts are on for this device.")
                showPushButtons(enable = false, enabled = true)
            } else {
                setPushStatus("Get a notification on this device the moment something is in stock.")
                showPushButtons(enable = true, enabled = false)
            }
        }
    }
}

private fun showPushButtons(enable: Boolean, enabled: Boolean) {
    el("enable-push").show(enable)
    el("test-push").show(enabled)
    el("disable-push").show(enabled)
}

private fun enablePush() {
    val key = store.current.vapidPublicKey ?: return
    scope.launch {
        setPushStatus("Asking for permission…")
        try {
            val json = scPush.subscribe(key).await<JsString>().toString()
            api.subscribe(ApiJson.decodeFromString(PushSubscriptionRequest.serializer(), json))
            setPushStatus("Push alerts are on for this device.")
            showPushButtons(enable = false, enabled = true)
        } catch (e: Throwable) {
            setPushStatus("Couldn't turn on push alerts: ${jsErrorText(e)}")
        }
    }
}

/** Kotlin/Wasm wraps JS errors as "Non-Kotlin exception X: msg of type '...'"; keep only "X: msg". */
private fun jsErrorText(e: Throwable): String =
    (e.message ?: e.toString()).removePrefix("Non-Kotlin exception ").substringBefore(" of type '")

private fun disablePush() {
    scope.launch {
        val endpoint = runCatching { scPush.unsubscribe().await<JsString>().toString() }.getOrDefault("")
        if (endpoint.isNotEmpty()) runCatching { api.unsubscribe(endpoint) }
        setPushStatus("Push alerts are off for this device. We'll email you instead.")
        showPushButtons(enable = true, enabled = false)
    }
}
