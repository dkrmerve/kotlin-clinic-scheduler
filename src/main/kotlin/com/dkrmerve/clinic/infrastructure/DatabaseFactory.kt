package com.dkrmerve.clinic.infrastructure

import com.dkrmerve.clinic.application.UnitOfWork
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.time.Duration
import javax.sql.DataSource

/** Connection pool settings; every value has an env var and a documented default (README, "Configuration"). */
data class DatabaseSettings(
    val jdbcUrl: String,
    val username: String,
    val password: String,
    val maximumPoolSize: Int = 10,
    val minimumIdle: Int = 2,
    val connectionTimeout: Duration = Duration.ofSeconds(10),
    val validationTimeout: Duration = Duration.ofSeconds(5),
    val maxLifetime: Duration = Duration.ofMinutes(30),
    val leakDetectionThreshold: Duration = Duration.ofSeconds(20),
    /** How long startup keeps retrying the first connection + migration before giving up. */
    val startupRetryBudget: Duration = Duration.ofSeconds(60),
)

/** Pool, migrations and the Exposed handle, in the order they must happen. */
object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    /** Opens the pool and migrates, retrying with backoff until [DatabaseSettings.startupRetryBudget] is spent. */
    fun connect(settings: DatabaseSettings): ConnectedDatabase {
        val dataSource = pool(settings)
        val deadline = System.nanoTime() + settings.startupRetryBudget.toNanos()
        var delay = Duration.ofSeconds(1)
        var attempt = 1
        while (true) {
            try {
                migrate(dataSource)
                break
            } catch (e: Exception) {
                if (System.nanoTime() + delay.toNanos() > deadline) {
                    dataSource.close()
                    throw IllegalStateException(
                        "Database at ${settings.jdbcUrl} not ready after $attempt attempt(s) within ${settings.startupRetryBudget.seconds}s: ${e.message}",
                        e,
                    )
                }
                log.warn("Database not ready (attempt {}): {}. Retrying in {}s", attempt, e.message, delay.seconds)
                Thread.sleep(delay.toMillis())
                delay = minOf(delay.multipliedBy(2), Duration.ofSeconds(10))
                attempt++
            }
        }
        return ConnectedDatabase(dataSource, exposed(dataSource))
    }

    fun pool(settings: DatabaseSettings): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = settings.jdbcUrl
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.maximumPoolSize
                minimumIdle = settings.minimumIdle
                connectionTimeout = settings.connectionTimeout.toMillis()
                validationTimeout = settings.validationTimeout.toMillis()
                maxLifetime = settings.maxLifetime.toMillis()
                leakDetectionThreshold = settings.leakDetectionThreshold.toMillis()
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "clinic-pool"
                // Do not fail on construction: startup retries the first real connection in connect().
                initializationFailTimeout = -1
            },
        )

    /** Common migrations plus the vendor folder (postgresql or h2), chosen from the JDBC driver metadata. */
    fun migrate(dataSource: DataSource) {
        val vendor = dataSource.connection.use { it.metaData.databaseProductName.lowercase() }
        val result =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
                .load()
                .migrate()
        log.info("Flyway: {} migration(s) applied, schema now at version {}", result.migrationsExecuted, result.targetSchemaVersion)
    }

    /** READ COMMITTED everywhere; nested transactions become savepoints (used by waitlist promotion). */
    fun exposed(dataSource: DataSource): Database =
        Database.connect(
            datasource = dataSource,
            databaseConfig =
                DatabaseConfig {
                    useNestedTransactions = true
                    defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED
                },
        )
}

class ConnectedDatabase(
    val dataSource: HikariDataSource,
    val database: Database,
) : AutoCloseable {
    /** A real round trip through the pool, used by /health/ready. */
    fun ping(): Boolean =
        try {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute("SELECT 1") }
            }
        } catch (e: Exception) {
            LoggerFactory.getLogger(ConnectedDatabase::class.java).warn("Database ping failed: {}", e.message)
            false
        }

    override fun close() = dataSource.close()
}

/** [UnitOfWork] over Exposed transactions: one JDBC transaction per use case, run on the I/O dispatcher. */
class ExposedUnitOfWork(
    private val database: Database,
) : UnitOfWork {
    override suspend fun <T> transaction(block: () -> T): T =
        withContext(Dispatchers.IO) {
            transaction(database) { block() }
        }

    override fun <T> savepoint(block: () -> T): T = transaction(database) { block() }
}
