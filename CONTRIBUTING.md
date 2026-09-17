# Contributing

Thanks for taking a look. This is a portfolio project, but it is run like a real one.

## Branching

```
main      <- always releasable; protected; only merges from develop (or hotfix/*)
develop   <- integration branch; feature branches merge here through pull requests
feature/* <- one topic per branch, e.g. feature/waitlist-expiry-job
fix/*     <- bug fixes, e.g. fix/dst-availability-gap
```

Rebase your branch on `develop` before opening a pull request; keep history linear inside a branch.

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`, `ci:`.
One logical change per commit; the subject line explains *why* when the diff does not.

## Before you open a pull request

- [ ] `./gradlew build` is green locally (ktlint, fast test suite on H2, coverage gates).
- [ ] `./gradlew integrationTest` is green (needs Docker; runs the API and repository suites on PostgreSQL).
- [ ] New behaviour has a test, and the test is listed in `docs/TEST-CATALOG.md` (see the note at its top on regenerating it).
- [ ] New error codes are added to `docs/errors.md` and the README error catalog.
- [ ] New configuration is added to `.env.example` and the README configuration table with its default.
- [ ] No secrets in code, tests or history. Local defaults for the compose PostgreSQL are the only credentials allowed.
- [ ] `docker compose up --build` still comes up healthy.

## Code style

ktlint with the `ktlint_official` code style enforces formatting (`./gradlew ktlintFormat` fixes most things).
Keep the layering: `domain` has no framework imports, `application` talks to ports only, `infrastructure`
and `api` are the only places that know about Exposed and Ktor respectively.
