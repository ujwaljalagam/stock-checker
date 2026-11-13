package app.stockchecker.domain

import app.stockchecker.model.Product
import app.stockchecker.model.Retailer
import app.stockchecker.model.StockCheckResult
import app.stockchecker.model.StockStatus

/** A source of in-store stock data for one retailer. Implementations live on the server. */
interface RetailerChecker {
    val retailer: Retailer

    suspend fun check(product: Product, zip: String, radiusMiles: Int): StockCheckResult

    /** Optional: resolve a display name for a SKU. */
    suspend fun lookupName(sku: String): String? = null
}

/** What happened when a watch was checked. */
data class StockCheckOutcome(
    val result: StockCheckResult,
    val shouldAlert: Boolean,
    val title: String,
    val body: String,
)

/**
 * The core use case: check a product near a ZIP code and decide whether the user should be alerted.
 */
class StockCheckUseCase(
    private val checkers: Map<String, RetailerChecker>,
    private val now: () -> Long,
    private val cooldownMs: Long = AlertPolicy.DEFAULT_COOLDOWN_MS,
) {
    suspend fun run(
        product: Product,
        zip: String,
        radiusMiles: Int,
        previous: StockStatus,
        lastAlertAt: Long?,
    ): StockCheckOutcome {
        val checker = checkers[product.retailer]
        val result = if (checker == null) {
            StockCheckResult(product, StockStatus.UNKNOWN, checkedAt = now(), error = "No checker for retailer '${product.retailer}'")
        } else {
            try {
                checker.check(product, zip, radiusMiles)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                StockCheckResult(product, StockStatus.UNKNOWN, checkedAt = now(), error = e.message ?: e::class.simpleName)
            }
        }
        // An error or UNKNOWN never counts as a change: keep comparing against the last real status.
        val effectivePrevious = previous
        val alert = result.error == null && AlertPolicy.shouldAlert(effectivePrevious, result.status, lastAlertAt, now(), cooldownMs)
        return StockCheckOutcome(
            result = result,
            shouldAlert = alert,
            title = AlertMessages.title(result),
            body = AlertMessages.body(result, zip),
        )
    }
}

/** Used by native apps that poll instead of receiving Web Push. */
object AlertTracker {
    fun newSince(alerts: List<app.stockchecker.model.Alert>, lastSeenId: Long): List<app.stockchecker.model.Alert> =
        alerts.filter { it.id > lastSeenId }.sortedBy { it.id }
}
