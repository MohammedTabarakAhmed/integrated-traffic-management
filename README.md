# ITMS — Integrated Traffic Management System

A national traffic platform built strictly against the ITMS BRD. **Status: Increment 0 (foundation). No business features yet.**

## Start here

1. `the project guide`: the delivery operating contract.
2. `docs/SCOPE_AND_ASSUMPTIONS.md`: scope, increments, key assumptions.
3. `docs/BRD_REQUIREMENTS.md` (438 numbered requirements) and `docs/BRD_SUPPLEMENT.md` (UATs, partners, reports, retention, out of scope).
4. Design documents in `docs/`:
   - HLD
   - DATA_MODEL
   - API_SPECIFICATION
   - ICD
   - LLD
   - SECURITY_DESIGN
   - TEST_STRATEGY
   - UI_PROTOTYPE
   - DECISIONS
5. `docs/PROGRESS.md` and `docs/TRACEABILITY.md`: current state.

## Build and verify

Prerequisites: JDK 21, Maven 3.9+, Python 3.10+. Docker is needed for integration tests once services exist.

```
tools/ci/verify.sh     # traceability check, build, tests, coverage gate, SBOM
tools/ci/security.sh   # secret scanning + SCA (needs gitleaks and an NVD mirror)
```

## One-command startup (local development)

Prerequisites: Docker running, plus the build prerequisites above. In Git Bash or any bash shell:

```
tools/dev/up.sh          # builds, starts PostgreSQL + Keycloak (realm "itms") + reference-data-service
tools/dev/smoke.sh       # end-to-end maker-checker check with real tokens
tools/dev/up.sh down     # stop
```

- First run generates `infra/local/.env` with random development secrets. It is git-ignored; never commit it.
- Development users are `dev.maker` (REFDATA_MAKER) and `dev.checker` (REFDATA_CHECKER).
- Services:
  - API: http://localhost:8081/v1/reference-lists
  - Keycloak: http://localhost:8180
- This local stack is **not** a production topology (see `docs/HLD.md` §8).

## What runs today

| Component | Status |
|---|---|
| `platform/itms-platform` | Correlation IDs, problem+json, OIDC resource server, audit outbox |
| `services/reference-data-service` | Bilingual code lists with maker-checker, history and audit (CC-110, CC-114) |
