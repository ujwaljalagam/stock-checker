package app.stockchecker.server

import app.stockchecker.domain.RetailerChecker
import app.stockchecker.server.auth.AuthService
import app.stockchecker.server.checkers.BestBuyChecker
import app.stockchecker.server.checkers.MockRetailerChecker
import app.stockchecker.server.db.Db
import app.stockchecker.server.db.Repository
import app.stockchecker.server.engine.AlertDispatcher
import app.stockchecker.server.engine.StockCheckEngine
import app.stockchecker.server.mail.ConsoleMailer
import app.stockchecker.server.mail.Mailer
import app.stockchecker.server.mail.SmtpMailer
import app.stockchecker.server.push.DisabledPushSender
import app.stockchecker.server.push.PushSender
import app.stockchecker.server.push.VapidPushSender
import app.stockchecker.server.routes.configureHttp
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.slf4j.LoggerFactory

/** Everything the HTTP layer needs. Built once in [module]. */
class Services(
    val config: AppConfig,
    val db: Db,
    val repo: Repository,
    val auth: AuthService,
    val checkers: List<RetailerChecker>,
    val push: PushSender,
    val mailer: Mailer,
    val dispatcher: AlertDispatcher,
    val engine: StockCheckEngine,
)

fun buildServices(config: AppConfig): Services {
    val log = LoggerFactory.getLogger("Startup")
    val db = Db.connect(config.db)
    val repo = Repository(db)
    val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 15_000 } }

    val mailer: Mailer = config.smtp?.let(::SmtpMailer) ?: ConsoleMailer()
    val push: PushSender = config.vapid?.let { VapidPushSender(it, http) } ?: DisabledPushSender

    val checkers = buildList<RetailerChecker> {
        config.bestBuyApiKey?.let { add(BestBuyChecker(it, http, baseUrl = config.bestBuyBaseUrl)) }
        if (config.enableMockRetailer) add(MockRetailerChecker())
    }
    if (config.vapid == null) log.warn("VAPID keys not set: Web Push disabled. Run ./gradlew :server:generateVapidKeys")
    if (config.smtp == null) log.warn("SMTP not configured: emails are printed to this log")
    if (config.devMode) log.warn("DEV MODE: sign-in codes are logged and returned by the API")
    log.info("Retailers: {}", checkers.joinToString { it.retailer.id })

    val dispatcher = AlertDispatcher(repo, push, mailer, config.publicBaseUrl)
    val engine = StockCheckEngine(
        repo = repo,
        checkers = checkers,
        dispatcher = dispatcher,
        intervalMs = config.checkIntervalSeconds * 1000,
        emailFallbackMs = config.emailFallbackMinutes * 60_000,
    )
    return Services(config, db, repo, AuthService(repo, mailer, config.devMode), checkers, push, mailer, dispatcher, engine)
}

/** Ktor entry point (see application.conf). */
fun Application.module() {
    val services = buildServices(AppConfig.from(environment.config))
    configureHttp(services)
    if (services.config.schedulerEnabled) {
        val job = services.engine.start(this)
        monitor.subscribe(ApplicationStopped) {
            job.cancel()
            services.db.close()
        }
    }
}
