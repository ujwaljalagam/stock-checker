package app.stockchecker.server.db

import app.stockchecker.server.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class Db(val database: Database, private val dataSource: HikariDataSource) : AutoCloseable {
    suspend fun <T> tx(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) { transaction(database) { block() } }

    override fun close() = dataSource.close()

    companion object {
        /** Opens the pool and applies Flyway migrations from resources/db/migration. */
        fun connect(config: AppConfig.DbConfig): Db {
            val hikari = HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            }
            val dataSource = HikariDataSource(hikari)
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
            return Db(Database.connect(dataSource), dataSource)
        }
    }
}
