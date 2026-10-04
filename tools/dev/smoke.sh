#!/usr/bin/env bash
# End-to-end smoke test against the running local stack, with real Keycloak tokens:
# grants come from iam-service, not the token (ADR-022) -> maker submits a change -> maker cannot approve ->
# checker approves (CC-114) -> item readable.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source infra/local/.env; set +a
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python
API=http://localhost:8081
TOKEN_URL=http://localhost:8180/realms/itms/protocol/openid-connect/token

token() {
  curl -sf "$TOKEN_URL" -d grant_type=password -d client_id=itms-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$2" \
    | "$PY" -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}
json_field() { "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

MAKER=$(token dev.maker "$DEV_MAKER_PASSWORD")
CHECKER=$(token dev.checker "$DEV_CHECKER_PASSWORD")
CODE="SMOKE_$(date +%s)"
IAM=http://localhost:8082

echo "0a. Tokens carry identity only: no permission claim (ADR-022)"
claims=$(printf '%s' "$MAKER" | "$PY" -c "import base64,json,sys; p=sys.stdin.read().split('.')[1]; print(sorted(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))))")
[[ "$claims" != *permissions* ]] || { echo "FAIL: token still carries permissions: $claims"; exit 1; }

echo "0b. iam-service holds the maker's grants"
perms=$(curl -sf -H "Authorization: Bearer $MAKER" "$IAM/v1/me/authorisation" | json_field "['grants'][0]['permissions']")
[[ "$perms" == *refdata.change.submit* ]] || { echo "FAIL: unexpected grants $perms"; exit 1; }

echo "1. Unauthenticated call is refused"
status=$(curl -s -o /dev/null -w '%{http_code}' "$API/v1/reference-lists")
[[ "$status" == 401 ]] || { echo "FAIL: expected 401, got $status"; exit 1; }

echo "2. Maker reads the seeded HOLD_TYPE list"
count=$(curl -sf -H "Authorization: Bearer $MAKER" "$API/v1/reference-lists/HOLD_TYPE/items" | json_field "['items'].__len__()")
(( count >= 11 )) || { echo "FAIL: expected >= 11 hold types, got $count"; exit 1; }

echo "3. Maker submits a new item ($CODE)"
change_id=$(curl -sf -H "Authorization: Bearer $MAKER" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$API/v1/reference-data-changes" \
  -d "{\"changeType\":\"CREATE_ITEM\",\"listCode\":\"HOLD_TYPE\",\"itemCode\":\"$CODE\",\"name\":{\"nl\":\"اختبار\",\"en\":\"Smoke test\"},\"effectiveFrom\":\"2026-01-01\",\"reason\":\"smoke test\"}" \
  | json_field "['id']")

echo "4. Maker cannot approve (no permission)"
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $MAKER" \
  "$API/v1/reference-data-changes/$change_id/approvals")
[[ "$status" == 403 ]] || { echo "FAIL: expected 403, got $status"; exit 1; }

echo "5. Checker approves"
state=$(curl -sf -X POST -H "Authorization: Bearer $CHECKER" -H 'X-Channel: BACKOFFICE' \
  "$API/v1/reference-data-changes/$change_id/approvals" | json_field "['status']")
[[ "$state" == APPROVED ]] || { echo "FAIL: expected APPROVED, got $state"; exit 1; }

echo "6. Item is now readable with full history"
name=$(curl -sf -H "Authorization: Bearer $MAKER" "$API/v1/reference-lists/HOLD_TYPE/items/$CODE" | json_field "['name']['en']")
[[ "$name" == "Smoke test" ]] || { echo "FAIL: unexpected name $name"; exit 1; }
versions=$(curl -sf -H "Authorization: Bearer $CHECKER" "$API/v1/reference-lists/HOLD_TYPE/items/$CODE/history" | json_field "['items'].__len__()")
[[ "$versions" == 1 ]] || { echo "FAIL: expected 1 version, got $versions"; exit 1; }

echo "SMOKE OK"
