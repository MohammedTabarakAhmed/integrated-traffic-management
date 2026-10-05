#!/usr/bin/env bash
# Security stage (SEC-021, SEC-023, DLV-004; ADR-036). Tools run from on-prem mirrors (NFR-022): the gitleaks image
# and the NVD data come from the Authority's registry and mirror, set per environment.
# SAST (SpotBugs + FindSecBugs) and the OpenAPI lint run on every build in tools/ci/verify.sh.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail=0

echo "== Secret scanning (gitleaks): the whole git history"
# The working tree is not scanned: infra/local/.env holds generated local secrets and is git-ignored.
GITLEAKS_IMAGE=${ITMS_GITLEAKS_IMAGE:-zricethezav/gitleaks:v8.28.0}
if command -v gitleaks >/dev/null; then
  gitleaks git . --config .gitleaks.toml --redact --no-banner || fail=1
elif command -v docker >/dev/null && docker image inspect "$GITLEAKS_IMAGE" >/dev/null 2>&1 \
     || docker pull -q "$GITLEAKS_IMAGE" >/dev/null 2>&1; then
  repo=$(pwd -W 2>/dev/null || pwd)   # Windows path under Git Bash
  MSYS_NO_PATHCONV=1 docker run --rm -v "$repo:/repo" "$GITLEAKS_IMAGE" \
    git /repo --config /repo/.gitleaks.toml --redact --no-banner || fail=1
else
  echo "MISSING TOOL: gitleaks (binary or image $GITLEAKS_IMAGE)"; fail=1
fi

echo "== SCA (OWASP Dependency-Check) against the on-prem NVD mirror"
if [[ -n "${ITMS_NVD_MIRROR_URL:-}" ]]; then
  mvn -B -ntp -Psecurity verify -DskipTests -Djacoco.skip=true -DnvdDatafeedUrl="$ITMS_NVD_MIRROR_URL" || fail=1
else
  echo "MISSING: ITMS_NVD_MIRROR_URL (NVD data feed mirror); Dependency-Check not run"; fail=1
fi

if command -v trivy >/dev/null; then echo "trivy: run per built image in the image stage"; fi
exit $fail
