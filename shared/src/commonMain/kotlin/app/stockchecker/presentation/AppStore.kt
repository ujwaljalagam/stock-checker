package app.stockchecker.presentation

import app.stockchecker.data.AlertRepository
import app.stockchecker.data.ApiException
import app.stockchecker.data.AuthRepository
import app.stockchecker.data.WatchRepository
import app.stockchecker.domain.Validation
import app.stockchecker.model.Alert
import app.stockchecker.model.CreateWatchRequest
import app.stockchecker.model.Retailer
import app.stockchecker.model.User
import app.stockchecker.model.Watch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Session {
    data object Unknown : Session
    data object SignedOut : Session
    data class CodeSent(val email: String, val devCode: String? = null) : Session
    data class SignedIn(val user: User) : Session
}

data class AppState(
    val session: Session = Session.Unknown,
    val retailers: List<Retailer> = emptyList(),
    val vapidPublicKey: String? = null,
    val watches: List<Watch> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
) {
    val signedIn: Boolean get() = session is Session.SignedIn
    val userEmail: String? get() = (session as? Session.SignedIn)?.user?.email
}

/** Something a Swift or JS caller can cancel. */
fun interface Cancellable {
    fun cancel()
}

/**
 * Shared presentation logic for every client (Android, iOS, web). Holds [AppState] and exposes
 * plain functions for user intents. All network work runs in [scope].
 */
class AppStore(
    private val auth: AuthRepository,
    private val watchRepo: WatchRepository,
    private val alertRepo: AlertRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    val current: AppState get() = _state.value

    /** Callback-style observation for Swift / Kotlin/Wasm callers. */
    fun observe(onEach: (AppState) -> Unit): Cancellable {
        val job: Job = scope.launch { state.collect { onEach(it) } }
        return Cancellable { job.cancel() }
    }

    fun start() = launchTask(showSpinner = true) {
        runCatching { auth.config() }.onSuccess { cfg ->
            _state.update { it.copy(retailers = cfg.retailers, vapidPublicKey = cfg.vapidPublicKey) }
        }
        if (auth.hasSession()) {
            val user = auth.me()
            _state.update { it.copy(session = Session.SignedIn(user)) }
            loadData()
        } else {
            _state.update { it.copy(session = Session.SignedOut) }
        }
    }

    fun requestCode(email: String) {
        val normalized = Validation.normalizeEmail(email)
        if (!Validation.isValidEmail(normalized)) {
            _state.update { it.copy(error = "Enter a valid email address.") }
            return
        }
        launchTask(showSpinner = true) {
            val response = auth.requestCode(normalized)
            _state.update {
                it.copy(session = Session.CodeSent(normalized, response.devCode), info = "We sent a 6-digit code to $normalized.")
            }
        }
    }

    fun verifyCode(code: String) {
        val pending = _state.value.session as? Session.CodeSent ?: return
        if (!Validation.isValidCode(code)) {
            _state.update { it.copy(error = "The code is 6 digits.") }
            return
        }
        launchTask(showSpinner = true) {
            val user = auth.verifyCode(pending.email, code.trim())
            _state.update { it.copy(session = Session.SignedIn(user), info = null) }
            loadData()
        }
    }

    fun changeEmail() = _state.update { it.copy(session = Session.SignedOut, error = null, info = null) }

    fun signOut() = launchTask {
        auth.signOut()
        _state.update { AppState(session = Session.SignedOut, retailers = it.retailers, vapidPublicKey = it.vapidPublicKey) }
    }

    fun refresh() = launchTask(showSpinner = true) { loadData() }

    fun addWatch(retailer: String, sku: String, zip: String, radiusMiles: Int, productName: String?) {
        val request = CreateWatchRequest(
            retailer = retailer,
            sku = sku.trim(),
            zip = zip.trim(),
            radiusMiles = radiusMiles,
            productName = productName?.trim()?.takeIf { it.isNotEmpty() },
        )
        val problems = Validation.validateWatch(request, _state.value.retailers.map { it.id }.toSet().ifEmpty { null })
        if (problems.isNotEmpty()) {
            _state.update { it.copy(error = problems.joinToString(" ")) }
            return
        }
        launchTask(showSpinner = true) {
            val watch = watchRepo.addWatch(request)
            _state.update { it.copy(watches = listOf(watch) + it.watches.filterNot { w -> w.id == watch.id }, info = "Watching ${watch.product.name ?: watch.product.sku}.") }
        }
    }

    fun toggleWatch(id: Long) {
        val watch = _state.value.watches.firstOrNull { it.id == id } ?: return
        launchTask {
            val updated = watchRepo.setActive(id, !watch.active)
            replaceWatch(updated)
        }
    }

    fun removeWatch(id: Long) = launchTask {
        watchRepo.deleteWatch(id)
        _state.update { it.copy(watches = it.watches.filterNot { w -> w.id == id }) }
    }

    fun checkNow(id: Long) = launchTask(showSpinner = true) {
        replaceWatch(watchRepo.checkNow(id))
        _state.update { it.copy(alerts = alertRepo.alerts()) }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, info = null) }

    private fun replaceWatch(updated: Watch) =
        _state.update { s -> s.copy(watches = s.watches.map { if (it.id == updated.id) updated else it }) }

    private suspend fun loadData() {
        val watches = watchRepo.watches()
        val alerts = alertRepo.alerts()
        _state.update { it.copy(watches = watches, alerts = alerts) }
    }

    private fun launchTask(showSpinner: Boolean = false, block: suspend () -> Unit): Job = scope.launch {
        if (showSpinner) _state.update { it.copy(loading = true, error = null) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            _state.update {
                if (e.isUnauthorized) it.copy(session = Session.SignedOut, watches = emptyList(), alerts = emptyList(), error = "Please sign in again.")
                else it.copy(error = e.message)
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = "Network error: ${e.message ?: e::class.simpleName}") }
        } finally {
            if (showSpinner) _state.update { it.copy(loading = false) }
        }
    }
}
