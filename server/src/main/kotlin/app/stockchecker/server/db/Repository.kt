package app.stockchecker.server.db

import app.stockchecker.model.Alert
import app.stockchecker.model.Product
import app.stockchecker.model.StockStatus
import app.stockchecker.model.User
import app.stockchecker.model.Watch
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

data class StoredSubscription(val id: Long, val userId: Long, val endpoint: String, val p256dh: String, val auth: String)

data class StoredWatch(val userId: Long, val lastAlertAt: Long?, val watch: Watch)

data class StoredLoginCode(val codeHash: String, val expiresAt: Long, val attempts: Int, val createdAt: Long)

data class FallbackCandidate(val alert: Alert, val email: String)

/** All SQL lives here. Every function runs inside its own transaction. */
class Repository(private val db: Db) {

    // ---------- users & auth ----------

    suspend fun findOrCreateUser(email: String, now: Long): User = db.tx {
        Users.selectAll().where { Users.email eq email }.singleOrNull()?.toUser()
            ?: User(Users.insert { it[Users.email] = email; it[createdAt] = now }[Users.id], email)
    }

    suspend fun userEmail(userId: Long): String? = db.tx {
        Users.selectAll().where { Users.id eq userId }.singleOrNull()?.get(Users.email)
    }

    suspend fun loginCode(email: String): StoredLoginCode? = db.tx {
        LoginCodes.selectAll().where { LoginCodes.email eq email }.singleOrNull()?.let {
            StoredLoginCode(it[LoginCodes.codeHash], it[LoginCodes.expiresAt], it[LoginCodes.attempts], it[LoginCodes.createdAt])
        }
    }

    suspend fun saveLoginCode(email: String, codeHash: String, expiresAt: Long, now: Long) = db.tx {
        LoginCodes.deleteWhere { LoginCodes.email eq email }
        LoginCodes.insert {
            it[LoginCodes.email] = email
            it[LoginCodes.codeHash] = codeHash
            it[LoginCodes.expiresAt] = expiresAt
            it[attempts] = 0
            it[createdAt] = now
        }
    }

    suspend fun incrementCodeAttempts(email: String) = db.tx {
        val current = LoginCodes.selectAll().where { LoginCodes.email eq email }.singleOrNull()?.get(LoginCodes.attempts) ?: return@tx
        LoginCodes.update({ LoginCodes.email eq email }) { it[attempts] = current + 1 }
    }

    suspend fun deleteLoginCode(email: String) = db.tx { LoginCodes.deleteWhere { LoginCodes.email eq email } }

    suspend fun createSession(tokenHash: String, userId: Long, now: Long, expiresAt: Long) = db.tx {
        Sessions.insert {
            it[Sessions.tokenHash] = tokenHash
            it[Sessions.userId] = userId
            it[createdAt] = now
            it[Sessions.expiresAt] = expiresAt
        }
    }

    suspend fun userForSession(tokenHash: String, now: Long): User? = db.tx {
        val session = Sessions.selectAll().where { Sessions.tokenHash eq tokenHash }.singleOrNull() ?: return@tx null
        if (session[Sessions.expiresAt] < now) return@tx null
        Users.selectAll().where { Users.id eq session[Sessions.userId] }.singleOrNull()?.toUser()
    }

    suspend fun deleteSession(tokenHash: String) = db.tx { Sessions.deleteWhere { Sessions.tokenHash eq tokenHash } }

    // ---------- watches ----------

    suspend fun watches(userId: Long): List<Watch> = db.tx {
        Watches.selectAll().where { Watches.userId eq userId }.orderBy(Watches.id, SortOrder.DESC).map { it.toWatch() }
    }

    suspend fun watch(userId: Long, id: Long): StoredWatch? = db.tx {
        Watches.selectAll().where { (Watches.id eq id) and (Watches.userId eq userId) }.singleOrNull()?.toStoredWatch()
    }

    suspend fun createWatch(
        userId: Long,
        retailer: String,
        sku: String,
        productName: String?,
        productUrl: String?,
        zip: String,
        radiusMiles: Int,
        now: Long,
    ): Watch = db.tx {
        val id = Watches.insert {
            it[Watches.userId] = userId
            it[Watches.retailer] = retailer
            it[Watches.sku] = sku
            it[Watches.productName] = productName
            it[Watches.productUrl] = productUrl
            it[Watches.zip] = zip
            it[Watches.radiusMiles] = radiusMiles
            it[active] = true
            it[lastStatus] = StockStatus.UNKNOWN.name
            it[createdAt] = now
        }[Watches.id]
        Watches.selectAll().where { Watches.id eq id }.single().toWatch()
    }

    suspend fun countWatches(userId: Long): Long = db.tx { Watches.selectAll().where { Watches.userId eq userId }.count() }

    suspend fun setWatchActive(userId: Long, id: Long, active: Boolean): Boolean = db.tx {
        Watches.update({ (Watches.id eq id) and (Watches.userId eq userId) }) { it[Watches.active] = active } > 0
    }

    suspend fun deleteWatch(userId: Long, id: Long): Boolean = db.tx {
        Alerts.deleteWhere { Alerts.watchId eq id }
        Watches.deleteWhere { (Watches.id eq id) and (Watches.userId eq userId) } > 0
    }

    /** Active watches never checked or last checked before [checkedBefore]. */
    suspend fun dueWatches(checkedBefore: Long, limit: Int): List<StoredWatch> = db.tx {
        Watches.selectAll()
            .where { (Watches.active eq true) and (Watches.lastCheckedAt.isNull() or (Watches.lastCheckedAt less checkedBefore)) }
            .orderBy(Watches.lastCheckedAt to SortOrder.ASC_NULLS_FIRST)
            .limit(limit)
            .map { it.toStoredWatch() }
    }

    suspend fun recordCheck(
        id: Long,
        status: StockStatus?,
        checkedAt: Long,
        storeName: String?,
        error: String?,
        productName: String?,
        alertedAt: Long?,
    ) = db.tx {
        Watches.update({ Watches.id eq id }) {
            if (status != null) it[lastStatus] = status.name
            it[lastCheckedAt] = checkedAt
            it[lastStoreName] = storeName
            it[lastError] = error?.take(500)
            if (productName != null) it[Watches.productName] = productName.take(200)
            if (alertedAt != null) it[lastAlertAt] = alertedAt
        }
    }

    // ---------- push subscriptions ----------

    suspend fun upsertSubscription(userId: Long, endpoint: String, p256dh: String, auth: String, userAgent: String?, now: Long) = db.tx {
        PushSubscriptions.deleteWhere { PushSubscriptions.endpoint eq endpoint }
        PushSubscriptions.insert {
            it[PushSubscriptions.userId] = userId
            it[PushSubscriptions.endpoint] = endpoint
            it[PushSubscriptions.p256dh] = p256dh
            it[PushSubscriptions.auth] = auth
            it[PushSubscriptions.userAgent] = userAgent?.take(300)
            it[createdAt] = now
            it[failures] = 0
        }
    }

    /** Owner of an existing subscription (used for unauthenticated pushsubscriptionchange). */
    suspend fun subscriptionOwner(endpoint: String): Long? = db.tx {
        PushSubscriptions.selectAll().where { PushSubscriptions.endpoint eq endpoint }.singleOrNull()?.get(PushSubscriptions.userId)
    }

    suspend fun deleteSubscription(endpoint: String, userId: Long? = null): Boolean = db.tx {
        PushSubscriptions.deleteWhere {
            if (userId == null) PushSubscriptions.endpoint eq endpoint
            else (PushSubscriptions.endpoint eq endpoint) and (PushSubscriptions.userId eq userId)
        } > 0
    }

    suspend fun subscriptions(userId: Long): List<StoredSubscription> = db.tx {
        PushSubscriptions.selectAll().where { PushSubscriptions.userId eq userId }.map {
            StoredSubscription(it[PushSubscriptions.id], userId, it[PushSubscriptions.endpoint], it[PushSubscriptions.p256dh], it[PushSubscriptions.auth])
        }
    }

    suspend fun markSubscriptionResult(id: Long, ok: Boolean, now: Long) = db.tx {
        val row = PushSubscriptions.selectAll().where { PushSubscriptions.id eq id }.singleOrNull() ?: return@tx
        PushSubscriptions.update({ PushSubscriptions.id eq id }) {
            if (ok) {
                it[lastSuccessAt] = now
                it[failures] = 0
            } else {
                it[failures] = row[PushSubscriptions.failures] + 1
            }
        }
    }

    // ---------- alerts ----------

    suspend fun createAlert(
        userId: Long,
        watchId: Long,
        status: StockStatus,
        title: String,
        body: String,
        url: String?,
        storeName: String?,
        receiptHash: String,
        now: Long,
    ): Alert = db.tx {
        val id = Alerts.insert {
            it[Alerts.userId] = userId
            it[Alerts.watchId] = watchId
            it[Alerts.status] = status.name
            it[Alerts.title] = title.take(300)
            it[Alerts.body] = body.take(1000)
            it[Alerts.url] = url?.take(500)
            it[Alerts.storeName] = storeName?.take(200)
            it[Alerts.receiptHash] = receiptHash
            it[createdAt] = now
        }[Alerts.id]
        Alerts.selectAll().where { Alerts.id eq id }.single().toAlert()
    }

    suspend fun alerts(userId: Long, limit: Int = 100): List<Alert> = db.tx {
        Alerts.selectAll().where { Alerts.userId eq userId }.orderBy(Alerts.id, SortOrder.DESC).limit(limit).map { it.toAlert() }
    }

    suspend fun markPushSent(alertId: Long, now: Long) = db.tx {
        Alerts.update({ (Alerts.id eq alertId) and Alerts.pushSentAt.isNull() }) { it[pushSentAt] = now }
    }

    suspend fun markEmailed(alertId: Long, now: Long) = db.tx {
        Alerts.update({ Alerts.id eq alertId }) { it[emailedAt] = now }
    }

    /** Records a delivery receipt. Returns false if the alert/receipt pair is unknown. */
    suspend fun markShown(alertId: Long, receiptHash: String, now: Long): Boolean = db.tx {
        val row = Alerts.selectAll().where { Alerts.id eq alertId }.singleOrNull() ?: return@tx false
        if (row[Alerts.receiptHash] != receiptHash) return@tx false
        if (row[Alerts.shownAt] == null) Alerts.update({ Alerts.id eq alertId }) { it[shownAt] = now }
        true
    }

    /** Alerts whose push was sent but never confirmed shown, and not yet emailed. */
    suspend fun alertsNeedingEmailFallback(createdBefore: Long): List<FallbackCandidate> = db.tx {
        Alerts.selectAll()
            .where { Alerts.shownAt.isNull() and Alerts.emailedAt.isNull() and Alerts.pushSentAt.isNotNull() and (Alerts.createdAt less createdBefore) }
            .limit(100)
            .mapNotNull { row ->
                val email = Users.selectAll().where { Users.id eq row[Alerts.userId] }.singleOrNull()?.get(Users.email)
                email?.let { FallbackCandidate(row.toAlert(), it) }
            }
    }

    // ---------- mapping ----------

    private fun ResultRow.toUser() = User(this[Users.id], this[Users.email])

    private fun ResultRow.toWatch() = Watch(
        id = this[Watches.id],
        product = Product(this[Watches.retailer], this[Watches.sku], this[Watches.productName], this[Watches.productUrl]),
        zip = this[Watches.zip],
        radiusMiles = this[Watches.radiusMiles],
        active = this[Watches.active],
        lastStatus = StockStatus.valueOf(this[Watches.lastStatus]),
        lastCheckedAt = this[Watches.lastCheckedAt],
        lastStoreName = this[Watches.lastStoreName],
        lastError = this[Watches.lastError],
        createdAt = this[Watches.createdAt],
    )

    private fun ResultRow.toStoredWatch() = StoredWatch(this[Watches.userId], this[Watches.lastAlertAt], toWatch())

    private fun ResultRow.toAlert() = Alert(
        id = this[Alerts.id],
        watchId = this[Alerts.watchId],
        title = this[Alerts.title],
        body = this[Alerts.body],
        url = this[Alerts.url],
        status = StockStatus.valueOf(this[Alerts.status]),
        storeName = this[Alerts.storeName],
        createdAt = this[Alerts.createdAt],
        pushSentAt = this[Alerts.pushSentAt],
        shownAt = this[Alerts.shownAt],
        emailedAt = this[Alerts.emailedAt],
    )
}
