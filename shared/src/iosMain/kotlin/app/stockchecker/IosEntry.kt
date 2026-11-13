package app.stockchecker

import app.stockchecker.data.StockCheckerApi
import app.stockchecker.data.TokenStore
import app.stockchecker.presentation.AppStore
import kotlinx.coroutines.MainScope
import platform.Foundation.NSUserDefaults

/** Persists the session token in NSUserDefaults. (Move to the Keychain before shipping.) */
class UserDefaultsTokenStore : TokenStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    override var token: String?
        get() = defaults.stringForKey(KEY)
        set(value) {
            if (value == null) defaults.removeObjectForKey(KEY) else defaults.setObject(value, KEY)
        }

    private companion object {
        const val KEY = "stockchecker.token"
    }
}

/** Entry point for Swift: `let store = IosEntryKt.createAppStore(baseUrl: "http://localhost:8080")`. */
fun createAppStore(baseUrl: String): AppStore {
    val api = StockCheckerApi(baseUrl, UserDefaultsTokenStore())
    return AppStore(api, api, api, MainScope())
}

/** Separate API handle for background polling (BGAppRefreshTask). */
fun createApi(baseUrl: String): StockCheckerApi = StockCheckerApi(baseUrl, UserDefaultsTokenStore())
