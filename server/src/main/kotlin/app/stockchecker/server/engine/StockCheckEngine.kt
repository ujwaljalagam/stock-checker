package app.stockchecker.server.engine

import app.stockchecker.domain.RetailerChecker
import app.stockchecker.domain.StockCheckUseCase
import app.stockchecker.model.Watch
import app.stockchecker.server.db.Repository
import app.stockchecker.server.db.StoredWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory

/**
 * Periodically checks every active watch, records the result, and dispatches alerts on restock.
 * Watches for the same retailer+SKU+ZIP are checked once per tick to be gentle with retailers.
 */
class StockCheckEngine(
    private val repo: Repository,
    checkers: List<RetailerChecker>,
    private val dispatcher: AlertDispatcher,
    private val intervalMs: Long,
    private val emailFallbackMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxConcurrentChecks: Int = 4,
) {
    private val log = LoggerFactory.getLogger(StockCheckEngine::class.java)
    private val useCase = StockCheckUseCase(checkers.associateBy { it.retailer.id }, now)

    data class TickReport(val checked: Int, val alerts: Int, val fallbackEmails: Int)

    fun start(scope: CoroutineScope, tickMs: Long = minOf(intervalMs, 30_000L)): Job = scope.launch {
        log.info("Stock checker running every {}s", intervalMs / 1000)
        while (isActive) {
            try {
                val report = tick()
                if (report.checked > 0 || report.fallbackEmails > 0) log.info("Tick: {}", report)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                log.error("Stock check tick failed", e)
            }
            delay(tickMs)
        }
    }

    /** One pass: check due watches, then send email fallbacks for unconfirmed pushes. */
    suspend fun tick(): TickReport {
        val due = repo.dueWatches(checkedBefore = now() - intervalMs + 1, limit = 200)
        var alerts = 0
        val groups = due.groupBy { Triple(it.watch.product.retailer, it.watch.product.sku.uppercase(), it.watch.zip) }
        val semaphore = Semaphore(maxConcurrentChecks)
        coroutineScope {
            groups.values.map { group ->
                launch {
                    semaphore.withPermit {
                        // Use the largest radius in the group so every watcher's stores are included.
                        val radius = group.maxOf { it.watch.radiusMiles }
                        for (stored in group) {
                            if (checkOne(stored, radius)) alerts++
                        }
                    }
                }
            }
        }
        val fallback = dispatcher.emailUnconfirmed(emailFallbackMs)
        return TickReport(due.size, alerts, fallback)
    }

    /** Checks a single watch right away (e.g. "Check now"). */
    suspend fun checkNow(stored: StoredWatch): Watch {
        checkOne(stored, stored.watch.radiusMiles)
        return repo.watch(stored.userId, stored.watch.id)?.watch ?: stored.watch
    }

    private suspend fun checkOne(stored: StoredWatch, radius: Int): Boolean {
        val watch = stored.watch
        val outcome = useCase.run(watch.product, watch.zip, radius, watch.lastStatus, stored.lastAlertAt)
        val result = outcome.result
        val alertAt = if (outcome.shouldAlert) now() else null
        repo.recordCheck(
            id = watch.id,
            status = if (result.error == null) result.status else null,
            checkedAt = now(),
            storeName = result.bestStore?.store?.name,
            error = result.error,
            productName = if (watch.product.name == null) result.product.name else null,
            alertedAt = alertAt,
        )
        if (outcome.shouldAlert) {
            dispatcher.dispatch(
                userId = stored.userId,
                watchId = watch.id,
                status = result.status,
                title = outcome.title,
                body = outcome.body,
                productUrl = result.product.url,
                storeName = result.bestStore?.store?.name,
            )
        }
        return outcome.shouldAlert
    }
}
