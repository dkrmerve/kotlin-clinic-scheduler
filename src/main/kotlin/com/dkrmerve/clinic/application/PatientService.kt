package com.dkrmerve.clinic.application

import com.dkrmerve.clinic.domain.Appointment
import com.dkrmerve.clinic.domain.Patient
import com.dkrmerve.clinic.domain.PatientId

data class CreatePatient(
    val name: String,
    val email: String,
)

class PatientService(
    private val patients: PatientRepository,
    private val appointments: AppointmentRepository,
    private val uow: UnitOfWork,
) {
    suspend fun create(cmd: CreatePatient): Patient =
        uow.transaction {
            val patient = Patient(id = PatientId.new(), name = cmd.name.trim(), email = cmd.email.trim().lowercase())
            patients.save(patient)
            patient
        }

    suspend fun get(id: PatientId): Patient = uow.transaction { patients.require(id) }

    /** Appointments of the patient, newest start first. */
    suspend fun appointments(
        id: PatientId,
        page: PageRequest,
    ): Page<Appointment> =
        uow.transaction {
            patients.require(id)
            appointments.forPatient(id, page)
        }
}
