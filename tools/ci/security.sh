#!/usr/bin/env bash
# Security stage (SEC-021, SEC-023, DLV-004). Tools must run from on-prem mirrors (NFR-022).
set -euo pipefail
cd "$(dirname "$0")/../.."
fail=0
if command -v gitleaks >/dev/null; then gitleaks detect --config .gitleaks.toml --redact || fail=1
else echo "MISSING TOOL: gitleaks (secret scanning) — install on CI runner"; fail=1; fi
mvn -B -ntp -Psecurity verify -DskipTests || fail=1   # OWASP Dependency-Check (needs NVD mirror)
if command -v trivy >/dev/null; then echo "trivy: run per built image in the image stage"; fi
exit $fail
