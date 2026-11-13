package app.stockchecker.model

import kotlinx.serialization.Serializable

/** Stock level at a store (or aggregated across nearby stores). */
@Serializable
enum class StockStatus {
    IN_STOCK,
    LIMITED,
    OUT_OF_STOCK,
    UNKNOWN;

    val isAvailable: Boolean get() = this == IN_STOCK || this == LIMITED
}

/** A retailer the server knows how to check. */
@Serializable
data class Retailer(
    val id: String,
    val name: String,
    /** Hint shown next to the SKU field, e.g. "6-7 digit Best Buy SKU". */
    val skuHint: String,
    val enabled: Boolean = true,
)

@Serializable
data class Product(
    val retailer: String,
    val sku: String,
    val name: String? = null,
    val url: String? = null,
)

/** A physical store location. */
@Serializable
data class Store(
    val id: String,
    val name: String,
    val address: String? = null,
    val zip: String? = null,
    val distanceMiles: Double? = null,
)

@Serializable
data class StoreAvailability(
    val store: Store,
    val status: StockStatus,
    val quantity: Int? = null,
)

/** Result of checking one product near one ZIP code. */
@Serializable
data class StockCheckResult(
    val product: Product,
    val status: StockStatus,
    val stores: List<StoreAvailability> = emptyList(),
    val checkedAt: Long,
    val error: String? = null,
) {
    /** Nearest store that has the product, if any. */
    val bestStore: StoreAvailability?
        get() = stores.filter { it.status.isAvailable }.minByOrNull { it.store.distanceMiles ?: Double.MAX_VALUE }
}

/** A user's request to be told when [product] is in stock near [zip]. */
@Serializable
data class Watch(
    val id: Long,
    val product: Product,
    val zip: String,
    val radiusMiles: Int,
    val active: Boolean,
    val lastStatus: StockStatus = StockStatus.UNKNOWN,
    val lastCheckedAt: Long? = null,
    val lastStoreName: String? = null,
    val lastError: String? = null,
    val createdAt: Long,
)

/** A notification that was raised for a watch. */
@Serializable
data class Alert(
    val id: Long,
    val watchId: Long,
    val title: String,
    val body: String,
    val url: String? = null,
    val status: StockStatus,
    val storeName: String? = null,
    val createdAt: Long,
    /** When at least one Web Push message was accepted by a push service. */
    val pushSentAt: Long? = null,
    /** When a device reported the notification was actually displayed (/api/alert-shown). */
    val shownAt: Long? = null,
    /** When the email fallback was sent. */
    val emailedAt: Long? = null,
)

@Serializable
data class User(val id: Long, val email: String)
