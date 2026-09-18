package com.dkrmerve.clinic.api

import com.dkrmerve.clinic.AppConfig
import com.dkrmerve.clinic.Dependencies
import com.dkrmerve.clinic.MutableClock
import com.dkrmerve.clinic.Repositories
import com.dkrmerve.clinic.TestDatabases
import com.dkrmerve.clinic.domain.SchedulingPolicy
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

const val TEST_SIGNING_KEY = "test-signing-key-that-is-at-least-32-chars-long"
const val TEST_ISSUER = "clinic-scheduler-test"

/** Monday 2027-01-11 09:00 in Amsterdam (CET, +01:00). Every API test starts here unless it moves the clock. */
val TEST_NOW: Instant = Instant.parse("2027-01-11T08:00:00Z")
val AMSTERDAM: ZoneId = ZoneId.of("Europe/Amsterdam")

fun testConfig(
    rateLimitPerMinute: Int = 100_000,
    devIssuerEnabled: Boolean = true,
    policy: SchedulingPolicy = SchedulingPolicy.DEFAULT,
    maxBodyBytes: Long = 65_536,
    trustProxyHeaders: Boolean = false,
) = AppConfig(
    port = 0,
    database = TestDatabases.settings(),
    policy = policy,
    auth = AuthSettings.DevHmac(TEST_SIGNING_KEY, TEST_ISSUER, devIssuerEnabled),
    rateLimitPerMinute = rateLimitPerMinute,
    trustProxyHeaders = trustProxyHeaders,
    maxBodyBytes = maxBodyBytes,
    shutdownGrace = Duration.ofMillis(100),
    shutdownTimeout = Duration.ofMillis(500),
)

/** Boots the real Ktor module against the shared test database and hands the test a typed client. */
fun clinicApp(
    config: AppConfig = testConfig(),
    repositories: Repositories = Repositories(),
    now: Instant = TEST_NOW,
    extraRoutes: Route.(Dependencies) -> Unit = {},
    block: suspend ClinicTestContext.() -> Unit,
) = testApplication {
    val clock = MutableClock(now)
    val deps = Dependencies(config, TestDatabases.shared, clock, repositories)
    application {
        clinicModule(deps)
        routing { extraRoutes(deps) }
    }
    val client =
        createClient {
            install(ContentNegotiation) { json(ApiJson) }
        }
    ClinicTestContext(deps, clock, client).block()
}

class ClinicTestContext(
    val deps: Dependencies,
    val clock: MutableClock,
    val client: HttpClient,
) {
    val time: ApiTime = deps.time

    /** Tokens are minted with the real clock: `exp` is verified against wall-clock time, not the test clock. */
    private val tokens = DevTokenIssuer(TEST_SIGNING_KEY, TEST_ISSUER, Clock.systemUTC())

    val admin: String = tokens.issue("admin-1", Role.Admin)
    val staff: String = tokens.issue("staff-1", Role.ClinicStaff)

    fun patientToken(patientId: String): String = tokens.issue(patientId, Role.Patient)

    fun expiredToken(): String = tokens.issue("staff-1", Role.ClinicStaff, ttl = Duration.ofHours(-2))

    fun foreignKeyToken(): String =
        DevTokenIssuer("another-key-that-is-also-at-least-32-chars", TEST_ISSUER, Clock.systemUTC())
            .issue("staff-1", Role.ClinicStaff)

    fun at(
        date: LocalDate,
        time: LocalTime,
    ): Instant = date.atTime(time).atZone(AMSTERDAM).toInstant()

    fun iso(instant: Instant): String = time.format(instant)

    suspend inline fun <reified T : Any> post(
        path: String,
        token: String?,
        body: T,
        crossinline configure: HttpRequestBuilder.() -> Unit = {
        },
    ): HttpResponse =
        client.post(path) {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
            configure()
        }

    /** A POST without a body (cancel, check-in, complete, no-show). */
    suspend fun post(
        path: String,
        token: String?,
    ): HttpResponse = client.post(path) { token?.let { bearerAuth(it) } }

    /** A POST with a raw body, for malformed payload tests. */
    suspend fun postRaw(
        path: String,
        token: String?,
        raw: String,
        type: ContentType = ContentType.Application.Json,
    ): HttpResponse =
        client.post(path) {
            token?.let { bearerAuth(it) }
            contentType(type)
            setBody(raw)
        }

    suspend fun get(
        path: String,
        token: String?,
        configure: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse =
        client.get(path) {
            token?.let { bearerAuth(it) }
            configure()
        }

    suspend fun createPractitioner(
        name: String = "Dr. ${UUID.randomUUID().toString().take(8)}",
        slotMinutes: Int = 15,
        bufferMinutes: Int = 0,
        maxAppointmentsPerDay: Int = 20,
        schedule: Map<String, WorkingWindowDto> = WEEKDAYS_9_TO_5,
    ): PractitionerResponse {
        val response =
            post(
                "/practitioners",
                admin,
                CreatePractitionerRequest(name, "General practice", slotMinutes, bufferMinutes, maxAppointmentsPerDay, schedule),
            )
        response.status shouldBe HttpStatusCode.Created
        return response.body()
    }

    suspend fun createPatient(name: String = "Patient ${UUID.randomUUID().toString().take(8)}"): PatientResponse {
        val response = post("/patients", staff, CreatePatientRequest(name, "${UUID.randomUUID()}@example.test"))
        response.status shouldBe HttpStatusCode.Created
        return response.body()
    }

    suspend fun book(
        practitionerId: String,
        patientId: String,
        start: Instant,
        type: String = "Consultation",
        token: String = staff,
    ): HttpResponse = post("/appointments", token, BookAppointmentRequest(practitionerId, patientId, type, iso(start)))

    suspend fun bookOk(
        practitionerId: String,
        patientId: String,
        start: Instant,
        type: String = "Consultation",
        token: String = staff,
    ): AppointmentResponse {
        val response = book(practitionerId, patientId, start, type, token)
        withClue("booking ${iso(start)}: ${response.bodyAsText()}") { response.status shouldBe HttpStatusCode.Created }
        return response.body()
    }

    suspend fun appointment(id: String): AppointmentResponse = get("/appointments/$id", staff).body()
}

val WEEKDAYS_9_TO_5: Map<String, WorkingWindowDto> =
    listOf("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY").associateWith { WorkingWindowDto("09:00", "17:00") }

/** Decodes a problem+json body. */
suspend fun HttpResponse.problem(): ProblemDetails = ApiJson.decodeFromString(ProblemDetails.serializer(), bodyAsText())

/** Asserts status and code, and that the detail is non-empty, then returns the problem for further checks. */
suspend fun HttpResponse.shouldBeProblem(
    status: HttpStatusCode,
    code: String,
): ProblemDetails {
    val text = bodyAsText()
    withClue("expected $status/$code but got ${this.status}: $text") {
        this.status shouldBe status
        headers["Content-Type"]?.startsWith("application/problem+json") shouldBe true
    }
    val problem = ApiJson.decodeFromString(ProblemDetails.serializer(), text)
    withClue(text) {
        problem.status shouldBe status.value
        problem.code shouldBe code
        problem.detail.shouldNotBeBlank()
        problem.type shouldBe PROBLEM_TYPE_BASE + code
    }
    return problem
}
