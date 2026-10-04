#!/usr/bin/env bash
# Portable CI entry point (ADR-013). Identical on a developer machine, GitLab or Jenkins.
# Stages: traceability -> build+unit+integration+coverage -> SBOM. Security scans: tools/ci/security.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
export PYTHONIOENCODING=utf-8
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python

echo "== [1/3] Traceability verifier tests + ledger check (DLV-001)"
"$PY" -m unittest discover -s tools/traceability
"$PY" tools/traceability/verify_ledger.py

echo "== [2/3] Maven build: enforcer, unit (Surefire), integration (Failsafe), JaCoCo >= 80% (NFR-050)"
mvn -B -ntp verify

echo "== [3/3] SBOM (SEC-023)"
ls target/bom.* 2>/dev/null || echo "SBOM produced per module once modules exist"
echo "CI verify: OK"
