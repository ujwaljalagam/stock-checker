package app.stockchecker.domain

import app.stockchecker.model.StockCheckResult
import app.stockchecker.model.StockStatus

/**
 * Decides when a stock change deserves an alert. Used by the server's scheduler and covered by
 * commonTest so every platform agrees on the rules.
 */
object AlertPolicy {
    /** Never alert twice for the same watch inside this window, even if stock flaps. */
    const val DEFAULT_COOLDOWN_MS: Long = 6 * 60 * 60 * 1000L

    fun shouldAlert(
        previous: StockStatus,
        current: StockStatus,
        lastAlertAt: Long?,
        now: Long,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Boolean {
        if (!current.isAvailable) return false
        // Only alert on a transition into stock; "still in stock" is not news.
        if (previous.isAvailable) return false
        return lastAlertAt == null || now - lastAlertAt >= cooldownMs
    }
}

/** Human-readable alert text. */
object AlertMessages {
    fun title(result: StockCheckResult): String {
        val name = result.product.name ?: "SKU ${result.product.sku}"
        return if (result.status == StockStatus.LIMITED) "Low stock: $name" else "In stock: $name"
    }

    fun body(result: StockCheckResult, zip: String): String {
        val best = result.bestStore
        val inStockCount = result.stores.count { it.status.isAvailable }
        return when {
            best == null -> "Available near $zip."
            inStockCount > 1 -> "${best.store.name}${best.store.distanceMiles?.let { " (${formatMiles(it)} mi)" } ?: ""} " +
                "and ${inStockCount - 1} more store${if (inStockCount > 2) "s" else ""} near $zip."
            else -> "${best.store.name}${best.store.distanceMiles?.let { " (${formatMiles(it)} mi)" } ?: ""} near $zip."
        }
    }

    private fun formatMiles(miles: Double): String {
        val tenths = kotlin.math.round(miles * 10).toLong()
        return "${tenths / 10}.${tenths % 10}"
    }
}
