package com.dkrmerve.clinic.api

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.dkrmerve.clinic.domain.Actor
import com.dkrmerve.clinic.domain.ForbiddenException
import com.dkrmerve.clinic.domain.PatientId
import com.dkrmerve.clinic.domain.UnauthenticatedException
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.concurrent.TimeUnit

/** How tokens are verified. Chosen at startup from environment variables (README, "Authentication"). */
sealed interface AuthSettings {
    /** Production: RS256 tokens from an external OIDC issuer, keys fetched from its JWKS endpoint. */
    data class Jwks(
        val jwksUrl: String,
        val issuer: String,
        val audience: String,
    ) : AuthSettings

    /** Development: HS256 tokens signed with a shared secret, optionally minted by POST /auth/token. */
    data class DevHmac(
        val signingKey: String,
        val issuer: String,
        val devIssuerEnabled: Boolean,
    ) : AuthSettings
}

enum class Role(
    val wire: String,
) {
    Patient("patient"),
    ClinicStaff("clinic_staff"),
    Admin("admin"),
    ;

    companion object {
        fun fromWire(raw: String?): Role? = entries.firstOrNull { it.wire == raw }
    }
}

/** The authenticated principal: the token's `sub` and `role` claims. */
data class Caller(
    val subject: String,
    val role: Role,
) {
    val actor: Actor get() = if (role == Role.Patient) Actor.Patient else Actor.Clinic

    fun requireRole(vararg allowed: Role) {
        if (role !in allowed) {
            throw ForbiddenException.forbiddenRole(
                "Role '${role.wire}' may not do this; requires ${allowed.joinToString(" or ") { it.wire }}",
            )
        }
    }

    /** Patients may only act on their own record (`sub` == patient id); staff and admins may act on anyone. */
    fun requireOwnerOrStaff(patientId: PatientId) {
        if (role == Role.Patient && subject != patientId.toString()) {
            throw ForbiddenException.notOwner("Patients may only access their own records")
        }
    }
}

const val AUTH_SCHEME = "clinic-jwt"
const val ROLE_CLAIM = "role"

fun ApplicationCall.caller(): Caller = principal<Caller>() ?: throw UnauthenticatedException("A valid bearer token is required")

fun AuthenticationConfig.clinicJwt(settings: AuthSettings) {
    jwt(AUTH_SCHEME) {
        realm = "clinic-scheduler"
        when (settings) {
            is AuthSettings.Jwks -> {
                val provider =
                    JwkProviderBuilder(URI(settings.jwksUrl).toURL())
                        .cached(10, 24, TimeUnit.HOURS)
                        .rateLimited(10, 1, TimeUnit.MINUTES)
                        .build()
                verifier(provider, settings.issuer) {
                    withAudience(settings.audience)
                    withClaimPresence("exp") // a token without exp would otherwise never expire
                    acceptLeeway(LEEWAY_SECONDS)
                }
            }

            is AuthSettings.DevHmac -> {
                verifier(
                    JWT
                        .require(Algorithm.HMAC256(settings.signingKey))
                        .withIssuer(settings.issuer)
                        .withClaimPresence("exp")
                        .acceptLeeway(LEEWAY_SECONDS)
                        .build(),
                )
            }
        }
        validate { credential ->
            val subject = credential.payload.subject?.takeIf { it.isNotBlank() }
            val role = Role.fromWire(credential.payload.getClaim(ROLE_CLAIM).asString())
            if (subject != null && role != null) Caller(subject, role) else null
        }
        challenge { _, _ -> call.respondProblem(UnauthenticatedException("A valid bearer token is required")) }
    }
}

/** Mints HS256 development tokens. Only wired when AUTH_DEV_ISSUER_ENABLED=true; never for production. */
class DevTokenIssuer(
    private val signingKey: String,
    private val issuer: String,
    private val clock: Clock,
) {
    fun issue(
        subject: String,
        role: Role,
        ttl: Duration = Duration.ofHours(1),
    ): String {
        val now = clock.instant()
        return JWT
            .create()
            .withIssuer(issuer)
            .withSubject(subject)
            .withClaim(ROLE_CLAIM, role.wire)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(ttl)))
            .sign(Algorithm.HMAC256(signingKey))
    }
}

private const val LEEWAY_SECONDS = 5L
