package app.stockchecker.server.db

import org.jetbrains.exposed.v1.core.Table

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = varchar("email", 254)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object LoginCodes : Table("login_codes") {
    val email = varchar("email", 254)
    val codeHash = varchar("code_hash", 64)
    val expiresAt = long("expires_at")
    val attempts = integer("attempts")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(email)
}

object Sessions : Table("sessions") {
    val tokenHash = varchar("token_hash", 64)
    val userId = long("user_id")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

object Watches : Table("watches") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val retailer = varchar("retailer", 32)
    val sku = varchar("sku", 64)
    val productName = varchar("product_name", 200).nullable()
    val productUrl = varchar("product_url", 500).nullable()
    val zip = varchar("zip", 10)
    val radiusMiles = integer("radius_miles")
    val active = bool("active")
    val lastStatus = varchar("last_status", 16)
    val lastCheckedAt = long("last_checked_at").nullable()
    val lastStoreName = varchar("last_store_name", 200).nullable()
    val lastError = varchar("last_error", 500).nullable()
    val lastAlertAt = long("last_alert_at").nullable()
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object PushSubscriptions : Table("push_subscriptions") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val endpoint = varchar("endpoint", 1000)
    val p256dh = varchar("p256dh", 200)
    val auth = varchar("auth", 100)
    val userAgent = varchar("user_agent", 300).nullable()
    val createdAt = long("created_at")
    val lastSuccessAt = long("last_success_at").nullable()
    val failures = integer("failures")
    override val primaryKey = PrimaryKey(id)
}

object Alerts : Table("alerts") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val watchId = long("watch_id")
    val status = varchar("status", 16)
    val title = varchar("title", 300)
    val body = varchar("body", 1000)
    val url = varchar("url", 500).nullable()
    val storeName = varchar("store_name", 200).nullable()
    val receiptHash = varchar("receipt_hash", 64)
    val createdAt = long("created_at")
    val pushSentAt = long("push_sent_at").nullable()
    val shownAt = long("shown_at").nullable()
    val emailedAt = long("emailed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}
