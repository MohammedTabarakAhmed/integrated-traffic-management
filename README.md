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
tools/dev/up.sh          # builds, starts PostgreSQL (one per service) + Keycloak (realm "itms") + iam-service + reference-data-service
tools/dev/smoke.sh       # end-to-end maker-checker check with real tokens
tools/dev/up.sh down     # stop
```

- First run generates `infra/local/.env` with random development secrets. It is git-ignored; never commit it.
- Development users are `dev.iam-admin` (bootstrap IT_ADMINISTRATOR), `dev.maker` (REFDATA_MAKER) and `dev.checker` (REFDATA_CHECKER). Keycloak only authenticates them; `up.sh` grants their roles through the iam-service API (ADR-022).
- Services:
  - Reference data API: http://localhost:8081/v1/reference-lists
  - IAM API: http://localhost:8082/v1/roles
  - Keycloak: http://localhost:8180
- This local stack is **not** a production topology (see `docs/HLD.md` §8).

## What runs today

| Component | Status |
|---|---|
| `platform/itms-platform` | Correlation IDs, problem+json, OIDC resource server, authorisation filter with grants from iam-service (fail closed), audit outbox |
| `services/iam-service` | Roles, permission catalogue, role assignments with region/centre/module scopes, effective authorisation (CC-001, CC-002, CC-005) |
| `services/reference-data-service` | Bilingual code lists with maker-checker, history and audit (CC-110, CC-114) |
