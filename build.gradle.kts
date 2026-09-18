import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.ktlint)
    application
}

group = "com.dkrmerve"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

application {
    mainClass = "com.dkrmerve.clinic.ApplicationKt"
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.exposed)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgres)
    implementation(libs.micrometer.prometheus)
    implementation(libs.logback)
    implementation(libs.logstash.encoder)
    // Security floor: logstash-logback-encoder 9.0 ships Jackson 3.0.1 (CVE-2026-29062, CVE-2026-54512, CVE-2026-54513).
    implementation(platform(libs.jackson3.bom))

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.h2)
}

ktlint {
    version = "1.8.0"
}

fun Test.commonTestSetup(database: String) {
    useJUnitPlatform()
    systemProperty("kotest.framework.config.fqn", "com.dkrmerve.clinic.KotestProjectConfig")
    systemProperty("clinic.test.db", database)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Fast suite: every test, backed by in-memory H2 (MODE=PostgreSQL). This is what the Docker image build runs.
tasks.test {
    commonTestSetup("h2")
}

// Source of truth: the API and repository suites again, against a real PostgreSQL started by Testcontainers.
val integrationTest by tasks.registering(Test::class) {
    description = "Runs the API and repository suites against PostgreSQL (Testcontainers)."
    group = "verification"
    commonTestSetup("postgres")
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("com.dkrmerve.clinic.api.*")
        includeTestsMatching("com.dkrmerve.clinic.infrastructure.*")
    }
    shouldRunAfter(tasks.test)
}

tasks.check {
    dependsOn(integrationTest)
}

// Coverage gates (README, "Test strategy"). Kover verifies the overall bound; koverVerifyLayers reads the same
// XML report and applies one threshold per layer, printing the table that the README quotes.
// Only the process bootstrap (ApplicationKt: main + env wiring) is excluded from coverage.
kover {
    reports {
        filters { excludes { classes("com.dkrmerve.clinic.ApplicationKt") } }
        total {
            verify {
                rule("overall line coverage >= 90%") {
                    bound {
                        minValue = 90
                        coverageUnits = CoverageUnit.LINE
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
        }
    }
}

/** Layer -> (minimum line %, minimum branch % or null). */
val layerThresholds =
    mapOf(
        "domain" to (95 to 95),
        "application" to (90 to null),
        "api" to (85 to null),
        "infrastructure" to (85 to null),
    )

val koverVerifyLayers by tasks.registering {
    description = "Fails the build when a layer is below its line/branch coverage threshold."
    group = "verification"
    dependsOn(tasks.named("koverXmlReport"))
    val report = layout.buildDirectory.file("reports/kover/report.xml")
    inputs.file(report)
    val thresholds = layerThresholds
    doLast {
        val xml = report.get().asFile.readText()
        val packages = Regex("""<package name="([^"]+)">(.*?)</package>""", RegexOption.DOT_MATCHES_ALL)
        val counter = Regex("""<counter type="(LINE|BRANCH)" missed="(\d+)" covered="(\d+)"/>""")
        val failures = mutableListOf<String>()
        println("layer            lines      branches")
        packages.findAll(xml).forEach { pkg ->
            val layer = pkg.groupValues[1].substringAfterLast('/')
            val totals =
                counter
                    .findAll(pkg.groupValues[2])
                    .groupBy({ it.groupValues[1] }) { it.groupValues[2].toInt() to it.groupValues[3].toInt() }
                    .mapValues { (_, values) -> values.last() } // the last counter of each type is the package total

            fun pct(type: String): Double? =
                totals[type]?.let { (missed, covered) -> (missed + covered).takeIf { it > 0 }?.let { 100.0 * covered / it } }
            val lines = pct("LINE") ?: 100.0
            val branches = pct("BRANCH")
            println("%-16s %6.2f%%    %s".format(Locale.ROOT, layer, lines, branches?.let { "%6.2f%%".format(Locale.ROOT, it) } ?: "   n/a"))
            val (minLines, minBranches) = thresholds[layer] ?: return@forEach
            if (lines < minLines) failures += "$layer line coverage %.2f%% is below %d%%".format(Locale.ROOT, lines, minLines)
            if (minBranches != null &&
                (branches ?: 100.0) < minBranches
            ) {
                failures +=
                    "$layer branch coverage %.2f%% is below %d%%".format(Locale.ROOT, branches, minBranches)
            }
        }
        if (failures.isNotEmpty()) throw GradleException("Coverage gates violated:\n - " + failures.joinToString("\n - "))
    }
}

tasks.check {
    dependsOn(tasks.named("koverVerify"), koverVerifyLayers)
}
