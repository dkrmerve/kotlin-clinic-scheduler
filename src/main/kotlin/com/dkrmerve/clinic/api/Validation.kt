package com.dkrmerve.clinic.api

import java.util.UUID

/**
 * Request validation at the API edge. Unlike the domain invariants (which throw on the first problem), the
 * validator collects every field error so a client can fix its payload in one round trip.
 * Rendered as 400 `validation_failed` with an `errors` map keyed by field.
 */
class RequestValidationException(
    val errors: Map<String, String>,
) : RuntimeException("Request validation failed: $errors")

class Validator {
    private val errors = linkedMapOf<String, String>()

    fun check(
        field: String,
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) errors.putIfAbsent(field, message())
    }

    /** Parses with [parse]; records [message] under [field] when it returns null. */
    fun <T> parse(
        field: String,
        raw: String?,
        message: String,
        parse: (String) -> T?,
    ): T? {
        val value = raw?.let(parse)
        if (value == null) errors.putIfAbsent(field, message)
        return value
    }

    fun throwIfInvalid() {
        if (errors.isNotEmpty()) throw RequestValidationException(errors.toMap())
    }

    companion object {
        /** Runs [block] with a fresh validator and throws when it collected errors. */
        inline fun <T> validate(block: Validator.() -> T): T {
            val validator = Validator()
            val result = validator.block()
            validator.throwIfInvalid()
            return result
        }
    }
}

fun uuidOrNull(raw: String): UUID? =
    try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

/** Enum lookup by name, case-insensitive, without leaking `valueOf` exceptions. */
inline fun <reified E : Enum<E>> enumOrNull(raw: String): E? = enumValues<E>().firstOrNull { it.name.equals(raw, ignoreCase = true) }

inline fun <reified E : Enum<E>> allowedValues(): String = enumValues<E>().joinToString(", ") { it.name }
