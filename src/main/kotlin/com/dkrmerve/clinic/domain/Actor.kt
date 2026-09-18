package com.dkrmerve.clinic.domain

/** Who is performing an action: derived from the JWT role (`patient` acts as the patient, staff and admin as the clinic). */
enum class Actor {
    Patient,
    Clinic,
}
