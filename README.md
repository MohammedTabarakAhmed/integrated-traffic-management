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

## One-command startup

Not yet available. It arrives with the first runnable service (BRD reader's guide: "README that starts the whole system with one command").
