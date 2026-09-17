package com.dkrmerve.clinic

import com.dkrmerve.clinic.infrastructure.ConnectedDatabase
import com.dkrmerve.clinic.infrastructure.DatabaseFactory
import com.dkrmerve.clinic.infrastructure.DatabaseSettings
import io.kotest.core.config.AbstractProjectConfig
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Duration

/**
 * One database for the whole test run, chosen by the `clinic.test.db` system property:
 * `h2` (default, in-memory, PostgreSQL mode) or `postgres` (Testcontainers, the source of truth in CI).
 * Tests never share rows: every test creates its own practitioners and patients with fresh UUIDs.
 */
object TestDatabases {
    val isPostgres: Boolean = System.getProperty("clinic.test.db", "h2") == "postgres"

    private var container: PostgreSQLContainer<*>? = null

    val shared: ConnectedDatabase by lazy { DatabaseFactory.connect(settings()) }

    fun settings(): DatabaseSettings =
        if (isPostgres) {
            val pg =
                container ?: PostgreSQLContainer("postgres:16-alpine").also {
                    it.start()
                    container = it
                }
            DatabaseSettings(
                jdbcUrl = pg.jdbcUrl,
                username = pg.username,
                password = pg.password,
                maximumPoolSize = 8,
                startupRetryBudget = Duration.ofSeconds(30),
            )
        } else {
            DatabaseSettings(
                jdbcUrl = "jdbc:h2:mem:clinic;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                username = "sa",
                password = "",
                maximumPoolSize = 8,
                startupRetryBudget = Duration.ofSeconds(10),
            )
        }

    fun shutdown() {
        if (shared.dataSource.isRunning) shared.close()
        container?.stop()
    }
}

/** Referenced by build.gradle.kts through the kotest.framework.config.fqn system property. */
class KotestProjectConfig : AbstractProjectConfig() {
    override suspend fun beforeProject() {
        TestDatabases.shared
    }

    override suspend fun afterProject() {
        TestDatabases.shutdown()
    }
}
