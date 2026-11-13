package app.stockchecker.server.checkers

import app.stockchecker.domain.RetailerChecker
import app.stockchecker.model.Product
import app.stockchecker.model.Retailer
import app.stockchecker.model.StockCheckResult
import app.stockchecker.model.StockStatus
import app.stockchecker.model.Store
import app.stockchecker.model.StoreAvailability
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Real in-store availability from Best Buy's public Products API
 * (https://bestbuyapis.github.io/api-documentation/#store-availability).
 *
 *   GET https://api.bestbuy.com/v1/products/{sku}/stores.json?postalCode={zip}&apiKey={key}
 *
 * Returns the stores near the ZIP that have the SKU available for in-store pickup today. An empty list
 * means "not available nearby". Requires a free developer key (BESTBUY_API_KEY).
 */
class BestBuyChecker(
    private val apiKey: String,
    private val http: HttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val baseUrl: String = "https://api.bestbuy.com/v1",
) : RetailerChecker {
    override val retailer = Retailer(ID, "Best Buy (US)", "Best Buy SKU, e.g. 6525421")

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lookupName(sku: String): String? = runCatching {
        val response = http.get("$baseUrl/products/$sku.json") {
            parameter("show", "sku,name,url")
            parameter("apiKey", apiKey)
        }
        if (!response.status.isSuccess()) return null
        json.parseToJsonElement(response.bodyAsText()).jsonObject["name"]?.jsonPrimitive?.content
    }.getOrNull()

    override suspend fun check(product: Product, zip: String, radiusMiles: Int): StockCheckResult {
        val response = http.get("$baseUrl/products/${product.sku}/stores.json") {
            parameter("postalCode", zip)
            parameter("apiKey", apiKey)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            error("Best Buy API returned ${response.status.value}: ${text.take(120)}")
        }
        val root = json.parseToJsonElement(text).jsonObject
        val stores = root["stores"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .map { it.toAvailability() }
            .filter { (it.store.distanceMiles ?: 0.0) <= radiusMiles }
        val status = when {
            stores.any { it.status == StockStatus.IN_STOCK } -> StockStatus.IN_STOCK
            stores.isNotEmpty() -> StockStatus.LIMITED
            else -> StockStatus.OUT_OF_STOCK
        }
        return StockCheckResult(
            product = product.copy(url = product.url ?: "https://www.bestbuy.com/site/${product.sku}.p?skuId=${product.sku}"),
            status = status,
            stores = stores,
            checkedAt = now(),
        )
    }

    private fun JsonObject.toAvailability(): StoreAvailability {
        fun s(key: String) = this[key]?.jsonPrimitive?.content
        val low = this["lowStock"]?.jsonPrimitive?.booleanOrNull == true
        val address = listOfNotNull(s("address"), s("city"), s("state")).joinToString(", ").ifBlank { null }
        return StoreAvailability(
            store = Store(
                id = s("storeID") ?: s("storeId") ?: "?",
                name = "Best Buy ${s("name") ?: ""}".trim(),
                address = address,
                zip = s("postalCode"),
                distanceMiles = this["distance"]?.jsonPrimitive?.doubleOrNull,
            ),
            status = if (low) StockStatus.LIMITED else StockStatus.IN_STOCK,
        )
    }

    companion object {
        const val ID = "bestbuy"
    }
}
