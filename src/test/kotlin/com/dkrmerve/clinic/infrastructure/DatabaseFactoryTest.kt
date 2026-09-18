package com.dkrmerve.clinic.infrastructure

import com.dkrmerve.clinic.TestDatabases
import com.dkrmerve.clinic.domain.NOW
import com.dkrmerve.clinic.domain.patient
import com.dkrmerve.clinic.domain.practitioner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration

class DatabaseFactoryTest :
    FunSpec({

        test("connect against an unreachable database retries within the budget and then fails with a clear message") {
            val settings =
                DatabaseSettings(
                    jdbcUrl = "jdbc:postgresql://127.0.0.1:1/nowhere",
                    username = "x",
                    password = "x",
                    connectionTimeout = Duration.ofMillis(300),
                    startupRetryBudget = Duration.ofSeconds(2),
                )
            val error = shouldThrow<IllegalStateException> { DatabaseFactory.connect(settings) }
            error.message shouldContain "not ready after"
            error.message shouldContain "attempt"
        }

        test("connect against a reachable database migrates and answers ping; a closed pool answers false") {
            val connected = DatabaseFactory.connect(TestDatabases.settings())
            connected.ping() shouldBe true
            connected.close()
            connected.ping() shouldBe false
        }

        test("migrate is idempotent: running it twice applies nothing the second time") {
            DatabaseFactory.migrate(TestDatabases.shared.dataSource)
            DatabaseFactory.migrate(TestDatabases.shared.dataSource)
        }

        context("ExposedUnitOfWork") {
            val uow = ExposedUnitOfWork(TestDatabases.shared.database)
            val practitioners = ExposedPractitionerRepository()
            val patients = ExposedPatientRepository()

            test("a failure after the first insert rolls the whole transaction back: nothing is persisted") {
                val doctor = practitioner()
                shouldThrow<IllegalStateException> {
                    uow.transaction {
                        practitioners.save(doctor)
                        error("simulated failure after the first insert")
                    }
                }
                transaction(TestDatabases.shared.database) { practitioners.findById(doctor.id) }.shouldBeNull()
            }

            test("a savepoint failure rolls back only its own writes; the outer transaction commits") {
                val doctor = practitioner()
                val pat = patient()
                uow.transaction {
                    practitioners.save(doctor)
                    runCatching {
                        uow.savepoint {
                            patients.save(pat)
                            error("promotion failed")
                        }
                    }
                }
                transaction(TestDatabases.shared.database) {
                    practitioners.findById(doctor.id).shouldNotBeNull()
                    patients.findById(pat.id).shouldBeNull()
                }
            }

            test("a successful savepoint keeps its writes") {
                val pat = patient()
                uow.transaction { uow.savepoint { patients.save(pat) } }
                transaction(TestDatabases.shared.database) { patients.findById(pat.id) } shouldBe pat
            }

            test("the return value of the block is returned") {
                uow.transaction { NOW } shouldBe NOW
            }
        }
    })

/** Schema facts that only PostgreSQL can prove: vendor-specific migrations and the partial unique index. */
class PostgresSchemaTest :
    FunSpec({
        test("the vendor migration created the partial unique index on active appointments").config(enabled = TestDatabases.isPostgres) {
            val rows =
                transaction(TestDatabases.shared.database) {
                    exec("SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank") { rs ->
                        generateSequence { if (rs.next()) "${rs.getString(1)} ${rs.getString(2)} ${rs.getBoolean(3)}" else null }.toList()
                    }!!
                }
            val indexes =
                transaction(TestDatabases.shared.database) {
                    exec("SELECT indexdef FROM pg_indexes WHERE tablename = 'appointments'") { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }!!
                }
            val waitlistIndexes =
                transaction(TestDatabases.shared.database) {
                    exec("SELECT indexdef FROM pg_indexes WHERE tablename = 'waitlist_entries'") { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }!!
                }
            val tables =
                transaction(TestDatabases.shared.database) {
                    exec("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'") { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }!!
                }
            io.kotest.assertions.withClue("history=$rows indexes=$indexes waitlist=$waitlistIndexes tables=$tables") {
                listOf("2 ", "3 ", "4 ").forEach { version -> rows.any { it.startsWith(version) } shouldBe true }
                indexes.any { it.contains("ux_appointments_active_slot") && it.contains("WHERE") } shouldBe true
                waitlistIndexes.any { it.contains("ux_waitlist_waiting") && it.contains("UNIQUE") && it.contains("WHERE") } shouldBe true
                ("patient_no_shows" in tables) shouldBe false
            }
        }
    })
