#!/usr/bin/env python3
"""Generates docs/TEST-CATALOG.md from the JUnit XML that Gradle wrote for the last `test` and `integrationTest` runs.

    ./gradlew test integrationTest && python3 scripts/test-catalog.py

One row per test: suite, test name, the rules / error codes it exercises (extracted from the name), and the database(s)
it ran on. The "why it exists" text per suite lives in SUITES below and is the only hand-written part.
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS = {"H2": ROOT / "build/test-results/test", "PostgreSQL": ROOT / "build/test-results/integrationTest"}
OUT = ROOT / "docs/TEST-CATALOG.md"

SUITES: dict[str, str] = OrderedDict(
    [
        ("domain.SchedulingRulesTest", "Rules 1-6 and 8 as pure functions with a fixed clock: boundary tables (withData), property tests for slot alignment and interval intersection, availability generation."),
        ("domain.DstTest", "Both Europe/Amsterdam DST switches of 2027: slot enumeration in wall time, instants for duplicated/skipped hours, real durations across the switch."),
        ("domain.CancellationPolicyTest", "Rule 7 at exact boundaries (24 h, 24 h - 1 s, 2 h, 2 h - 1 s), clinic cancellations, configurable notice periods."),
        ("domain.NoShowPolicyTest", "Rule 8: rolling 90-day window (inclusive), retroactive no-shows, blockedUntil boundary, policy knobs."),
        ("domain.AppointmentStateMachineTest", "Rule 10 as a full transition matrix (every status pair) and rule 11 history entries."),
        ("domain.EntityInvariantsTest", "Every `invariant` in the aggregates throws a typed ValidationException with its code; ids, types, time helpers, exception catalog."),
        ("api.AuthTest", "JWT authentication (401 for missing/expired/foreign-key/malformed tokens) and role/ownership authorization (403)."),
        ("api.PractitionerApiTest", "POST/GET practitioners, time-off, availability endpoint including horizon and validation errors, Location headers."),
        ("api.PatientApiTest", "POST/GET patients, normalisation, pagination of a patient's appointments."),
        ("api.AppointmentApiTest", "The appointment lifecycle over HTTP: rule violations with codes, transitions, cancellation policy, no-show blocking, reschedule atomicity."),
        ("api.WaitlistApiTest", "Rule 9: joining, listing, lazy expiry, FIFO promotion with skips, promotion inside the cancel transaction and the documented failure decision."),
        ("api.ProblemMappingTest", "One case per DomainException subtype for the exhaustive status mapping, plus every framework failure (malformed JSON, 415, 413, 500 without leaks)."),
        ("api.OperationsTest", "Health endpoints, metrics, correlation ids, rate limiting, the development token issuer."),
        ("api.ConcurrencyTest", "Real parallel HTTP requests against PostgreSQL: row locks, partial unique index, optimistic version column. Skipped on H2."),
        ("infrastructure.ExposedRepositoriesTest", "Every repository method against the database, status codec, optimistic locking, the partial unique index (PostgreSQL)."),
        ("infrastructure.DatabaseFactoryTest", "Startup retry budget, readiness ping, idempotent migrations, transaction rollback and savepoint semantics."),
        ("infrastructure.PostgresSchemaTest", "Vendor-specific migration and partial index exist on PostgreSQL."),
        ("AppConfigTest", "Environment parsing: defaults, every variable, OIDC mode selection, all problems reported together."),
    ]
)

KNOWN_CODES = re.compile(
    r"\b(validation_failed|malformed_request|unsupported_media_type|payload_too_large|invalid_practitioner|slot_incompatible|"
    r"invalid_schedule|invalid_patient|invalid_time_off|invalid_appointment|invalid_policy|unauthenticated|forbidden_role|not_owner|"
    r"patient_blocked|\w+_not_found|route_not_found|method_not_allowed|invalid_transition|slot_taken|patient_conflict|"
    r"daily_capacity_reached|waitlist_duplicate|no_show_before_start|concurrent_modification|conflict|outside_working_hours|"
    r"slot_misaligned|practitioner_unavailable|outside_booking_horizon|cancellation_window_closed|rate_limited|internal_error)\b"
)
RULE = re.compile(r"\b[Rr]ule (\d+)\b")
TOPICS = [
    (re.compile(r"DST|spring-forward|fall-back", re.I), "DST"),
    (re.compile(r"parallel", re.I), "concurrency"),
    (re.compile(r"waitlist|promot", re.I), "rule 9"),
    (re.compile(r"reschedul", re.I), "rule 10"),
    (re.compile(r"history", re.I), "rule 11"),
    (re.compile(r"no-show|blocked", re.I), "rule 8"),
    (re.compile(r"cancel", re.I), "rule 7"),
    (re.compile(r"capacity", re.I), "rule 6"),
    (re.compile(r"buffer|overlap", re.I), "rule 2"),
    (re.compile(r"horizon", re.I), "rule 4"),
    (re.compile(r"align|window|closing|opening", re.I), "rule 1"),
    (re.compile(r"time-off|time off", re.I), "rule 3"),
    (re.compile(r"token|401|403|owner|role", re.I), "auth"),
    (re.compile(r"pagin|page", re.I), "pagination"),
    (re.compile(r"Location", re.I), "201 Location"),
]


def tags(suite: str, name: str, context: str) -> str:
    text = f"{context} {name}"
    found: list[str] = []
    for m in RULE.finditer(text):
        found.append(f"rule {m.group(1)}")
    for pattern, tag in TOPICS:
        if pattern.search(text) and tag not in found:
            found.append(tag)
    for m in KNOWN_CODES.finditer(text):
        code = f"`{m.group(1)}`"
        if code not in found:
            found.append(code)
    return ", ".join(found) if found else "-"


def collect() -> dict[str, dict[str, set[str]]]:
    """suite -> test name -> set of databases it ran on (skipped runs excluded)."""
    catalog: dict[str, dict[str, set[str]]] = {}
    for db, folder in RESULTS.items():
        for xml in sorted(folder.glob("TEST-*.xml")):
            root = ET.parse(xml).getroot()
            suite = root.attrib["name"].replace("com.dkrmerve.clinic.", "")
            for case in root.iter("testcase"):
                if case.find("skipped") is not None:
                    continue
                catalog.setdefault(suite, {}).setdefault(case.attrib["name"], set()).add(db)
    return catalog


def main() -> int:
    catalog = collect()
    if not catalog:
        print("no test results found; run ./gradlew test integrationTest first", file=sys.stderr)
        return 1
    total = sum(len(tests) for tests in catalog.values())
    lines = [
        "# Test catalog",
        "",
        "Generated by `scripts/test-catalog.py` from the JUnit XML of the last `./gradlew test` (H2) and `./gradlew integrationTest`",
        "(PostgreSQL via Testcontainers) runs. Re-run it after adding tests; the suite descriptions live in the script.",
        "Data-driven tests (`withData`) appear once per row of their table.",
        "",
        f"**{total} distinct tests.** Database column: which suite runs each test (skipped runs are not listed).",
        "",
    ]
    ordered = list(SUITES) + sorted(s for s in catalog if s not in SUITES)
    for suite in ordered:
        tests = catalog.get(suite)
        if not tests:
            continue
        lines += [f"## {suite}", "", SUITES.get(suite, "(no description yet: add it to SUITES in scripts/test-catalog.py)"), "",
                  "| Test | Rules / codes exercised | Database |", "|------|-------------------------|----------|"]
        for name, dbs in tests.items():
            context, _, leaf = name.rpartition(" > ")
            pure = suite.startswith("domain.") or suite == "AppConfigTest"
            db = "none (pure unit test)" if pure else " + ".join(sorted(dbs, key=lambda d: d != "H2"))
            lines.append(f"| {name.replace('|', '\\|')} | {tags(suite, leaf, context)} | {db} |")
        lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} with {total} tests")
    return 0


if __name__ == "__main__":
    sys.exit(main())
