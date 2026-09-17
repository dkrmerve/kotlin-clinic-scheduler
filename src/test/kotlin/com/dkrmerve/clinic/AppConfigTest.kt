package com.dkrmerve.clinic

import com.dkrmerve.clinic.api.AuthSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Period
import java.time.ZoneId

class AppConfigTest :
    FunSpec({
        val minimal =
            mapOf(
                "DATABASE_URL" to "jdbc:postgresql://db:5432/clinic",
                "AUTH_DEV_SIGNING_KEY" to "0123456789012345678901234567890123456789",
            )

        test("minimal environment yields the documented defaults") {
            val config = AppConfig.fromEnvironment(minimal)
            config.port shouldBe 8080
            config.database.username shouldBe "clinic"
            config.database.password shouldBe "clinic"
            config.database.maximumPoolSize shouldBe 10
            config.database.minimumIdle shouldBe 2
            config.database.connectionTimeout shouldBe Duration.ofSeconds(10)
            config.database.validationTimeout shouldBe Duration.ofSeconds(5)
            config.database.maxLifetime shouldBe Duration.ofMinutes(30)
            config.database.leakDetectionThreshold shouldBe Duration.ofSeconds(20)
            config.database.startupRetryBudget shouldBe Duration.ofSeconds(60)
            config.policy.zone shouldBe ZoneId.of("Europe/Amsterdam")
            config.policy.bookingHorizon shouldBe Period.ofDays(60)
            config.policy.freeCancellationNotice shouldBe Duration.ofHours(24)
            config.policy.minimumCancellationNotice shouldBe Duration.ofHours(2)
            config.policy.noShowLimit shouldBe 3
            config.policy.noShowWindow shouldBe Duration.ofDays(90)
            config.policy.blockDuration shouldBe Duration.ofDays(30)
            config.rateLimitPerMinute shouldBe 120
            config.maxBodyBytes shouldBe 65_536
            config.shutdownGrace shouldBe Duration.ofMillis(2_000)
            config.shutdownTimeout shouldBe Duration.ofMillis(10_000)
            val auth = config.auth.shouldBeInstanceOf<AuthSettings.DevHmac>()
            auth.devIssuerEnabled shouldBe false
            auth.issuer shouldBe "clinic-scheduler-dev"
        }

        test("every variable is honoured when set") {
            val config =
                AppConfig.fromEnvironment(
                    minimal +
                        mapOf(
                            "PORT" to "9090",
                            "DATABASE_USER" to "u",
                            "DATABASE_PASSWORD" to "p",
                            "DB_POOL_MAX" to "3",
                            "DB_POOL_MIN_IDLE" to "1",
                            "DB_CONNECTION_TIMEOUT_MS" to "1000",
                            "DB_VALIDATION_TIMEOUT_MS" to "500",
                            "DB_MAX_LIFETIME_MS" to "60000",
                            "DB_LEAK_DETECTION_MS" to "0",
                            "DB_STARTUP_RETRY_SECONDS" to "5",
                            "CLINIC_ZONE" to "Europe/Istanbul",
                            "BOOKING_HORIZON_DAYS" to "30",
                            "FREE_CANCEL_HOURS" to "48",
                            "MIN_CANCEL_HOURS" to "12",
                            "NO_SHOW_LIMIT" to "2",
                            "NO_SHOW_WINDOW_DAYS" to "30",
                            "BLOCK_DAYS" to "7",
                            "RATE_LIMIT_PER_MINUTE" to "10",
                            "MAX_BODY_BYTES" to "2048",
                            "SHUTDOWN_GRACE_MS" to "1",
                            "SHUTDOWN_TIMEOUT_MS" to "2",
                            "AUTH_DEV_ISSUER" to "me",
                            "AUTH_DEV_ISSUER_ENABLED" to "yes",
                        ),
                )
            config.port shouldBe 9090
            config.database.username shouldBe "u"
            config.database.maximumPoolSize shouldBe 3
            config.database.leakDetectionThreshold shouldBe Duration.ZERO
            config.database.startupRetryBudget shouldBe Duration.ofSeconds(5)
            config.policy.zone shouldBe ZoneId.of("Europe/Istanbul")
            config.policy.bookingHorizon shouldBe Period.ofDays(30)
            config.policy.freeCancellationNotice shouldBe Duration.ofHours(48)
            config.policy.minimumCancellationNotice shouldBe Duration.ofHours(12)
            config.policy.noShowLimit shouldBe 2
            config.policy.blockDuration shouldBe Duration.ofDays(7)
            config.rateLimitPerMinute shouldBe 10
            config.maxBodyBytes shouldBe 2048
            val auth = config.auth.shouldBeInstanceOf<AuthSettings.DevHmac>()
            auth.issuer shouldBe "me"
            auth.devIssuerEnabled shouldBe true
        }

        test("JWKS mode is selected when the three OIDC variables are present") {
            val config =
                AppConfig.fromEnvironment(
                    mapOf(
                        "DATABASE_URL" to "jdbc:postgresql://db:5432/clinic",
                        "AUTH_JWKS_URL" to "https://issuer.example/.well-known/jwks.json",
                        "AUTH_ISSUER" to "https://issuer.example/",
                        "AUTH_AUDIENCE" to "clinic-api",
                    ),
                )
            config.auth shouldBe AuthSettings.Jwks("https://issuer.example/.well-known/jwks.json", "https://issuer.example/", "clinic-api")
        }

        test("all problems are reported at once: missing DATABASE_URL, short signing key, bad numbers, bad zone, bad boolean") {
            val error =
                shouldThrow<ConfigException> {
                    AppConfig.fromEnvironment(
                        mapOf(
                            "AUTH_DEV_SIGNING_KEY" to "short",
                            "PORT" to "99999",
                            "CLINIC_ZONE" to "Mars/Olympus",
                            "AUTH_DEV_ISSUER_ENABLED" to "maybe",
                            "MIN_CANCEL_HOURS" to "48",
                            "DB_POOL_MAX" to "lots",
                        ),
                    )
                }
            listOf(
                "DATABASE_URL: is required",
                "AUTH_DEV_SIGNING_KEY",
                "PORT",
                "CLINIC_ZONE",
                "AUTH_DEV_ISSUER_ENABLED",
                "MIN_CANCEL_HOURS",
                "DB_POOL_MAX",
            ).forEach { error.message shouldContain it }
        }

        test("a non-PostgreSQL DATABASE_URL, a partial JWKS setup, a non-https JWKS URL and dev issuer in JWKS mode are rejected") {
            val error =
                shouldThrow<ConfigException> {
                    AppConfig.fromEnvironment(
                        mapOf(
                            "DATABASE_URL" to "jdbc:mysql://db/clinic",
                            "AUTH_JWKS_URL" to "http://issuer.example/jwks",
                            "AUTH_DEV_ISSUER_ENABLED" to "true",
                        ),
                    )
                }
            error.message shouldContain "DATABASE_URL: must be a JDBC PostgreSQL URL"
            error.message shouldContain "must be set together"
            error.message shouldContain "must be an https:// URL"
            error.message shouldContain "cannot be enabled together with JWKS mode"
        }
    })
