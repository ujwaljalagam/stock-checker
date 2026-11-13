package app.stockchecker.server

import io.ktor.server.config.ApplicationConfig

data class AppConfig(
    val devMode: Boolean,
    val publicBaseUrl: String,
    val db: DbConfig,
    val vapid: VapidConfig?,
    val smtp: SmtpConfig?,
    val bestBuyApiKey: String?,
    val bestBuyBaseUrl: String,
    val enableMockRetailer: Boolean,
    val checkIntervalSeconds: Long,
    val schedulerEnabled: Boolean,
    /** Email an alert if no device confirmed it was shown within this many minutes. */
    val emailFallbackMinutes: Long,
    val webStaticDir: String?,
    val corsHosts: List<String>,
) {
    data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int = 5)
    data class VapidConfig(val publicKey: String, val privateKey: String, val subject: String)
    data class SmtpConfig(
        val host: String,
        val port: Int,
        val username: String?,
        val password: String?,
        val from: String,
        val startTls: Boolean,
    )

    companion object {
        fun from(config: ApplicationConfig): AppConfig {
            fun str(path: String): String? = config.propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() }
            fun bool(path: String, default: Boolean) = str(path)?.toBooleanStrictOrNull() ?: default
            fun long(path: String, default: Long) = str(path)?.toLongOrNull() ?: default

            val vapidPublic = str("stockchecker.vapid.publicKey")
            val vapidPrivate = str("stockchecker.vapid.privateKey")
            val smtpHost = str("stockchecker.smtp.host")
            return AppConfig(
                devMode = bool("stockchecker.devMode", true),
                publicBaseUrl = (str("stockchecker.publicBaseUrl") ?: "http://localhost:8080").trimEnd('/'),
                db = DbConfig(
                    url = str("stockchecker.db.url") ?: "jdbc:h2:file:./server/data/stockchecker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                    user = str("stockchecker.db.user") ?: "sa",
                    password = str("stockchecker.db.password") ?: "",
                ),
                vapid = if (vapidPublic != null && vapidPrivate != null) {
                    VapidConfig(vapidPublic, vapidPrivate, str("stockchecker.vapid.subject") ?: "mailto:admin@example.com")
                } else {
                    null
                },
                smtp = smtpHost?.let {
                    SmtpConfig(
                        host = it,
                        port = str("stockchecker.smtp.port")?.toIntOrNull() ?: 587,
                        username = str("stockchecker.smtp.username"),
                        password = str("stockchecker.smtp.password"),
                        from = str("stockchecker.smtp.from") ?: "Stock Checker <alerts@example.com>",
                        startTls = bool("stockchecker.smtp.startTls", true),
                    )
                },
                bestBuyApiKey = str("stockchecker.retailers.bestBuyApiKey"),
                bestBuyBaseUrl = str("stockchecker.retailers.bestBuyBaseUrl") ?: "https://api.bestbuy.com/v1",
                enableMockRetailer = bool("stockchecker.retailers.enableMock", true),
                checkIntervalSeconds = long("stockchecker.scheduler.intervalSeconds", 300),
                schedulerEnabled = bool("stockchecker.scheduler.enabled", true),
                emailFallbackMinutes = long("stockchecker.scheduler.emailFallbackMinutes", 10),
                webStaticDir = str("stockchecker.web.staticDir"),
                corsHosts = str("stockchecker.corsHosts")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
        }
    }
}
