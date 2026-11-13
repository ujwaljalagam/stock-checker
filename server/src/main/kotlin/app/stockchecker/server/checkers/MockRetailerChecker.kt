package app.stockchecker.server.checkers

import app.stockchecker.domain.RetailerChecker
import app.stockchecker.model.Product
import app.stockchecker.model.Retailer
import app.stockchecker.model.StockCheckResult
import app.stockchecker.model.StockStatus
import app.stockchecker.model.Store
import app.stockchecker.model.StoreAvailability
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

/**
 * Deterministic fake retailer for development and tests. Behaviour is driven by the SKU:
 *
 * - `INSTOCK-*`   always in stock
 * - `LOW-*`       always limited stock
 * - `OOS-*`       always out of stock
 * - `RESTOCK-N-*` out of stock for the first N checks of that SKU+ZIP, then in stock (N defaults to 1)
 * - `ERROR-*`     the "retailer" fails
 * - anything else flips between out of stock and in stock every [flipPeriodMs], offset by a hash of the SKU
 */
class MockRetailerChecker(
    private val now: () -> Long = System::currentTimeMillis,
    private val flipPeriodMs: Long = 10 * 60 * 1000L,
) : RetailerChecker {
    override val retailer = Retailer(
        id = ID,
        name = "Demo Mart (mock)",
        skuHint = "Try RESTOCK-1-PS5, INSTOCK-LEGO or OOS-GPU",
    )

    private val checks = ConcurrentHashMap<String, Int>()

    override suspend fun lookupName(sku: String): String = "Demo product ${sku.uppercase()}"

    override suspend fun check(product: Product, zip: String, radiusMiles: Int): StockCheckResult {
        val sku = product.sku.uppercase()
        val count = checks.merge("$sku|$zip", 1, Int::plus)!!
        val status = when {
            sku.startsWith("INSTOCK") -> StockStatus.IN_STOCK
            sku.startsWith("LOW") -> StockStatus.LIMITED
            sku.startsWith("OOS") -> StockStatus.OUT_OF_STOCK
            sku.startsWith("ERROR") -> error("Demo Mart is down (mock error)")
            sku.startsWith("RESTOCK") -> {
                val n = sku.split('-').getOrNull(1)?.toIntOrNull() ?: 1
                if (count > n) StockStatus.IN_STOCK else StockStatus.OUT_OF_STOCK
            }
            else -> if (((now() / flipPeriodMs) + sku.hashCode().absoluteValue) % 2 == 0L) StockStatus.IN_STOCK else StockStatus.OUT_OF_STOCK
        }
        val stores = storesNear(zip, radiusMiles).mapIndexed { index, store ->
            // Only the nearest store gets the stock, which keeps alert text predictable.
            val storeStatus = if (status.isAvailable && index == 0) status else StockStatus.OUT_OF_STOCK
            StoreAvailability(store, storeStatus, quantity = if (storeStatus == StockStatus.IN_STOCK) 7 else if (storeStatus == StockStatus.LIMITED) 1 else 0)
        }
        return StockCheckResult(
            product = product.copy(name = product.name ?: lookupName(sku), url = product.url ?: "https://example.com/demo/$sku"),
            status = status,
            stores = stores,
            checkedAt = now(),
        )
    }

    private fun storesNear(zip: String, radiusMiles: Int): List<Store> =
        listOf(1.2, 4.8, 11.5).filter { it <= radiusMiles }.ifEmpty { listOf(radiusMiles.toDouble()) }.mapIndexed { i, miles ->
            Store(id = "demo-$zip-$i", name = listOf("Demo Mart Downtown", "Demo Mart Plaza", "Demo Mart Outlet")[i % 3], zip = zip, distanceMiles = miles)
        }

    companion object {
        const val ID = "mock"
    }
}
