package app.stockchecker.data

import app.stockchecker.model.Alert
import app.stockchecker.model.ConfigResponse
import app.stockchecker.model.CreateWatchRequest
import app.stockchecker.model.PushSubscriptionRequest
import app.stockchecker.model.RequestCodeResponse
import app.stockchecker.model.User
import app.stockchecker.model.Watch

interface AuthRepository {
    fun hasSession(): Boolean
    suspend fun config(): ConfigResponse
    suspend fun requestCode(email: String): RequestCodeResponse
    suspend fun verifyCode(email: String, code: String): User
    suspend fun me(): User
    suspend fun signOut()
}

interface WatchRepository {
    suspend fun watches(): List<Watch>
    suspend fun addWatch(request: CreateWatchRequest): Watch
    suspend fun setActive(id: Long, active: Boolean): Watch
    suspend fun deleteWatch(id: Long)
    suspend fun checkNow(id: Long): Watch
}

interface AlertRepository {
    suspend fun alerts(): List<Alert>
}

interface PushRepository {
    suspend fun subscribe(subscription: PushSubscriptionRequest)
    suspend fun unsubscribe(endpoint: String)
    suspend fun sendTest()
}

/** Where the session token lives. Each platform supplies a persistent implementation. */
interface TokenStore {
    var token: String?
}

class InMemoryTokenStore(override var token: String? = null) : TokenStore

class ApiException(val status: Int, message: String) : Exception(message) {
    val isUnauthorized: Boolean get() = status == 401
}
