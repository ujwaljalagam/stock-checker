@file:OptIn(ExperimentalWasmJsInterop::class)

package app.stockchecker.web

import kotlin.js.Promise

/** Bindings for window.scPush defined in push.js. */
external object scPush : JsAny {
    fun support(): String
    fun registerServiceWorker(): Promise<JsString>
    fun current(): Promise<JsString>
    fun subscribe(vapidPublicKey: String): Promise<JsString>
    fun unsubscribe(): Promise<JsString>
    fun formatTime(epochMs: Double): String
    fun onAlert(callback: () -> Unit)
}
