package com.dkrmerve.clinic

import com.dkrmerve.clinic.api.AuthSettings
import com.dkrmerve.clinic.domain.SchedulingPolicy
import com.dkrmerve.clinic.infrastructure.DatabaseSettings
import java.time.DateTimeException
import java.time.Duration
import java.time.Period
import java.time.ZoneId

/** Thrown at startup with every configuration problem at once, so an operator fixes them in one go. */
class ConfigException(
    problems: List<String>,
) : RuntimeException("Invalid configuration:\n - " + problems.joinToString("\n - "))

/** Everything the process needs, parsed and validated once from environment variables (README, "Configuration"). */
data class AppConfig(
    val port: Int,
    val database: DatabaseSettings,
    val policy: SchedulingPolicy,
    val auth: AuthSettings,
    val rateLimitPerMinute: Int,
    val maxBodyBytes: Long,
    val shutdownGrace: Duration,
    val shutdownTimeout: Duration,
) {
    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): AppConfig {
            val reader = EnvReader(env)
            val config =
                with(reader) {
                    AppConfig(
                        port = int("PORT", 8080, 1..65535),
                        database =
                            DatabaseSettings(
                                jdbcUrl =
                                    required("DATABASE_URL", "must be a JDBC PostgreSQL URL (jdbc:postgresql://host:5432/db)") {
                                        it.startsWith("jdbc:postgresql://")
                                    },
                                username = string("DATABASE_USER", "clinic"),
                                password = string("DATABASE_PASSWORD", "clinic"),
                                maximumPoolSize = int("DB_POOL_MAX", 10, 1..200),
                                minimumIdle = int("DB_POOL_MIN_IDLE", 2, 0..200),
                                connectionTimeout = millis("DB_CONNECTION_TIMEOUT_MS", 10_000),
                                validationTimeout = millis("DB_VALIDATION_TIMEOUT_MS", 5_000),
                                maxLifetime = millis("DB_MAX_LIFETIME_MS", 1_800_000),
                                leakDetectionThreshold = millis("DB_LEAK_DETECTION_MS", 20_000),
                                startupRetryBudget = Duration.ofSeconds(int("DB_STARTUP_RETRY_SECONDS", 60, 0..3600).toLong()),
                            ),
                        policy = policy(),
                        auth = auth(),
                        rateLimitPerMinute = int("RATE_LIMIT_PER_MINUTE", 120, 1..100_000),
                        maxBodyBytes = int("MAX_BODY_BYTES", 65_536, 1024..10_485_760).toLong(),
                        shutdownGrace = millis("SHUTDOWN_GRACE_MS", 2_000),
                        shutdownTimeout = millis("SHUTDOWN_TIMEOUT_MS", 10_000),
                    )
                }
            reader.throwIfInvalid()
            return config
        }

        private fun EnvReader.policy(): SchedulingPolicy {
            val zone =
                string("CLINIC_ZONE", "Europe/Amsterdam").let { raw ->
                    try {
                        ZoneId.of(raw)
                    } catch (_: DateTimeException) {
                        problem("CLINIC_ZONE", "'$raw' is not a valid zone id (e.g. Europe/Amsterdam)")
                        ZoneId.of("Europe/Amsterdam")
                    }
                }
            val freeCancelHours = int("FREE_CANCEL_HOURS", 24, 0..24 * 30)
            val minCancelHours = int("MIN_CANCEL_HOURS", 2, 0..24 * 30)
            if (minCancelHours > freeCancelHours) problem("MIN_CANCEL_HOURS", "must not exceed FREE_CANCEL_HOURS")
            return SchedulingPolicy(
                zone = zone,
                bookingHorizon = Period.ofDays(int("BOOKING_HORIZON_DAYS", 60, 1..365)),
                freeCancellationNotice = Duration.ofHours(freeCancelHours.toLong()),
                minimumCancellationNotice = Duration.ofHours(minOf(minCancelHours, freeCancelHours).toLong()),
                noShowLimit = int("NO_SHOW_LIMIT", 3, 1..100),
                noShowWindow = Duration.ofDays(int("NO_SHOW_WINDOW_DAYS", 90, 1..3650).toLong()),
                blockDuration = Duration.ofDays(int("BLOCK_DAYS", 30, 1..3650).toLong()),
            )
        }

        private fun EnvReader.auth(): AuthSettings {
            val jwksUrl = optional("AUTH_JWKS_URL")
            val issuer = optional("AUTH_ISSUER")
            val audience = optional("AUTH_AUDIENCE")
            val jwksVars = listOf(jwksUrl, issuer, audience)
            if (jwksVars.any { it != null }) {
                if (jwksVars.any { it == null }) {
                    problem("AUTH_JWKS_URL", "AUTH_JWKS_URL, AUTH_ISSUER and AUTH_AUDIENCE must be set together")
                }
                if (jwksUrl != null && !jwksUrl.startsWith("https://")) problem("AUTH_JWKS_URL", "must be an https:// URL")
                if (bool("AUTH_DEV_ISSUER_ENABLED", false)) {
                    problem("AUTH_DEV_ISSUER_ENABLED", "the development token issuer cannot be enabled together with JWKS mode")
                }
                return AuthSettings.Jwks(jwksUrl ?: "", issuer ?: "", audience ?: "")
            }
            val key =
                required("AUTH_DEV_SIGNING_KEY", "must be at least 32 characters (or configure AUTH_JWKS_URL for OIDC)") {
                    it.length >= 32
                }
            return AuthSettings.DevHmac(
                signingKey = key,
                issuer = string("AUTH_DEV_ISSUER", "clinic-scheduler-dev"),
                devIssuerEnabled = bool("AUTH_DEV_ISSUER_ENABLED", false),
            )
        }
    }
}

/** Reads typed values, records every problem instead of failing on the first one. */
private class EnvReader(
    private val env: Map<String, String>,
) {
    private val problems = mutableListOf<String>()

    fun problem(
        name: String,
        message: String,
    ) {
        problems += "$name: $message"
    }

    fun optional(name: String): String? = env[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun string(
        name: String,
        default: String,
    ): String = optional(name) ?: default

    /** Missing values and values failing [check] are both recorded; [message] describes the expected shape. */
    fun required(
        name: String,
        message: String,
        check: (String) -> Boolean,
    ): String {
        val value = optional(name)
        if (value == null) {
            problem(name, "is required; $message")
            return ""
        }
        if (!check(value)) problem(name, message)
        return value
    }

    fun int(
        name: String,
        default: Int,
        range: IntRange,
    ): Int {
        val raw = optional(name) ?: return default
        val value = raw.toIntOrNull()
        if (value == null || value !in range) {
            problem(name, "'$raw' must be an integer in $range")
            return default
        }
        return value
    }

    fun millis(
        name: String,
        default: Long,
    ): Duration = Duration.ofMillis(int(name, default.toInt(), 0..3_600_000).toLong())

    fun bool(
        name: String,
        default: Boolean,
    ): Boolean =
        when (optional(name)?.lowercase()) {
            null -> {
                default
            }

            "true", "1", "yes" -> {
                true
            }

            "false", "0", "no" -> {
                false
            }

            else -> {
                problem(name, "'${env[name]}' must be true or false")
                default
            }
        }

    fun throwIfInvalid() {
        if (problems.isNotEmpty()) throw ConfigException(problems)
    }
}
