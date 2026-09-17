package com.dkrmerve.clinic

import com.dkrmerve.clinic.api.ApiTime
import com.dkrmerve.clinic.api.AuthSettings
import com.dkrmerve.clinic.api.DevTokenIssuer
import com.dkrmerve.clinic.application.AppointmentRepository
import com.dkrmerve.clinic.application.AppointmentService
import com.dkrmerve.clinic.application.BookingEngine
import com.dkrmerve.clinic.application.PatientRepository
import com.dkrmerve.clinic.application.PatientService
import com.dkrmerve.clinic.application.PractitionerRepository
import com.dkrmerve.clinic.application.PractitionerService
import com.dkrmerve.clinic.application.TimeOffRepository
import com.dkrmerve.clinic.application.WaitlistRepository
import com.dkrmerve.clinic.application.WaitlistService
import com.dkrmerve.clinic.domain.SchedulingRules
import com.dkrmerve.clinic.infrastructure.ConnectedDatabase
import com.dkrmerve.clinic.infrastructure.ExposedAppointmentRepository
import com.dkrmerve.clinic.infrastructure.ExposedPatientRepository
import com.dkrmerve.clinic.infrastructure.ExposedPractitionerRepository
import com.dkrmerve.clinic.infrastructure.ExposedTimeOffRepository
import com.dkrmerve.clinic.infrastructure.ExposedUnitOfWork
import com.dkrmerve.clinic.infrastructure.ExposedWaitlistRepository
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Clock

/** The repository set; tests substitute a failing repository to prove transactional guarantees. */
data class Repositories(
    val practitioners: PractitionerRepository = ExposedPractitionerRepository(),
    val timeOff: TimeOffRepository = ExposedTimeOffRepository(),
    val patients: PatientRepository = ExposedPatientRepository(),
    val appointments: AppointmentRepository = ExposedAppointmentRepository(),
    val waitlist: WaitlistRepository = ExposedWaitlistRepository(),
)

/** Manual constructor injection: the whole object graph in one readable place, no DI framework. */
class Dependencies(
    val config: AppConfig,
    val database: ConnectedDatabase,
    val clock: Clock,
    repositories: Repositories = Repositories(),
) {
    val rules = SchedulingRules(config.policy, clock)
    val time = ApiTime(config.policy.zone)
    val unitOfWork = ExposedUnitOfWork(database.database)
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    private val engine =
        BookingEngine(repositories.practitioners, repositories.patients, repositories.timeOff, repositories.appointments, rules)

    val practitioners = PractitionerService(repositories.practitioners, repositories.timeOff, repositories.appointments, rules, unitOfWork)
    val patients = PatientService(repositories.patients, repositories.appointments, unitOfWork)
    val waitlist = WaitlistService(repositories.waitlist, repositories.practitioners, repositories.patients, engine, rules, unitOfWork)
    val appointments =
        AppointmentService(
            practitioners = repositories.practitioners,
            patients = repositories.patients,
            appointments = repositories.appointments,
            engine = engine,
            waitlist = waitlist,
            rules = rules,
            policy = config.policy,
            uow = unitOfWork,
        )

    /** Token lifetimes are wall-clock (verifiers compare `exp` with real time), so the issuer never uses the domain clock. */
    val devTokenIssuer: DevTokenIssuer? =
        (config.auth as? AuthSettings.DevHmac)
            ?.takeIf { it.devIssuerEnabled }
            ?.let { DevTokenIssuer(it.signingKey, it.issuer, Clock.systemUTC()) }
}
