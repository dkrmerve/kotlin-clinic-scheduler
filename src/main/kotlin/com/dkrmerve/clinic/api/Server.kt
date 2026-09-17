package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.Dependencies
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

val ApiJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

const val CORRELATION_HEADER = "X-Correlation-Id"

/** The Ktor module: plugins in dependency order, then routes. Reused verbatim by tests via testApplication. */
fun Application.clinicModule(deps: Dependencies) {
    val config = deps.config
    install(CallId) {
        retrieveFromHeader(CORRELATION_HEADER)
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() && it.length <= 128 }
        replyToHeader(CORRELATION_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        disableDefaultColors()
        callIdMdc("correlationId")
        filter { call -> !call.request.path().startsWith("/health") && call.request.path() != "/metrics" }
    }
    install(MicrometerMetrics) {
        registry = deps.metrics
    }
    install(ContentNegotiation) {
        json(ApiJson)
    }
    install(RequestBodyLimit) {
        bodyLimit { config.maxBodyBytes }
    }
    install(RateLimit) {
        register(WRITES_RATE_LIMIT) {
            rateLimiter(limit = config.rateLimitPerMinute, refillPeriod = 60.seconds)
            requestKey { call -> call.request.origin.remoteAddress }
        }
    }
    install(Authentication) {
        clinicJwt(config.auth)
    }
    install(StatusPages) {
        problemDetails(deps.time)
    }
    routing {
        operationsRoutes(deps)
        deps.devTokenIssuer?.let { devTokenRoutes(it) }
        schedulingRoutes(deps)
    }
}
