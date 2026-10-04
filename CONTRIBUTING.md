# Contributing to ITMS

## Branching (ADR-012)

- `main` is protected. Changes go through merge requests with at least one reviewer who is not the author (secure code review, SEC-021).
- Branch name format: `<type>/<REQ-ID>-<short-desc>`, for example `feat/CC-040-fee-definitions`.
- No public remotes. Code lives only in the Authority's on-prem repository (SEC-025).

## Commit messages

```
<type>(<scope>): <summary>

Refs: CC-040, CC-041
```

- `type`: one of `feat`, `fix`, `test`, `docs`, `build`, `ci`, `refactor`, `sec`, `chore`.
- `Refs:` must list the BRD requirement IDs affected. Use `Refs: none` for pure tooling.

## Tests

- Tag each test with requirement IDs:
  - Java: `@Tag("VF-032")`.
  - Python/TS: a `@req VF-032` comment.
- Untagged tests are allowed (helpers, architecture tests) but do not count as traceability evidence.

## Before pushing

```
tools/ci/verify.sh        # traceability + build + tests + coverage + SBOM
tools/ci/security.sh      # secrets + SCA (needs gitleaks and an NVD mirror)
```

## Definition of Done

See `docs/DEFINITION_OF_DONE.md`. Never mark a requirement `[x]` without evidence (the project guide §9).
