package com.dkrmerve.clinic

import com.dkrmerve.clinic.api.clinicModule
import com.dkrmerve.clinic.infrastructure.DatabaseFactory
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.system.exitProcess

/**
 * Process bootstrap: config, database, dependency graph, HTTP server, shutdown hook.
 * Excluded from coverage (see build.gradle.kts); everything it calls is covered.
 */
fun main() {
    val log = LoggerFactory.getLogger("com.dkrmerve.clinic.Application")
    val config =
        try {
            AppConfig.fromEnvironment()
        } catch (e: ConfigException) {
            log.error(e.message)
            exitProcess(1)
        }
    val database =
        try {
            DatabaseFactory.connect(config.database)
        } catch (e: IllegalStateException) {
            log.error("Cannot start: {}", e.message)
            exitProcess(2)
        }
    val deps = Dependencies(config, database, Clock.systemUTC())
    val server =
        embeddedServer(
            Netty,
            configure = {
                connector {
                    host = "0.0.0.0"
                    port = config.port
                }
                shutdownGracePeriod = config.shutdownGrace.toMillis()
                shutdownTimeout = config.shutdownTimeout.toMillis()
                requestReadTimeoutSeconds = 30
                responseWriteTimeoutSeconds = 30
                maxInitialLineLength = 4096
                maxHeaderSize = 16_384
                maxChunkSize = 8192
            },
        ) { clinicModule(deps) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            log.info("Shutdown requested: draining in-flight requests")
            server.stop(config.shutdownGrace.toMillis(), config.shutdownTimeout.toMillis())
            database.close()
        },
    )
    log.info("Clinic Scheduler listening on port {} (zone {})", config.port, config.policy.zone)
    server.start(wait = true)
}
