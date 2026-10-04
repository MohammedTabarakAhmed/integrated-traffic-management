#!/usr/bin/env bash
# End-to-end smoke test against the running local stack, with real Keycloak tokens:
# grants come from iam-service, not the token (ADR-022) -> maker submits a change -> maker cannot approve ->
# checker approves (CC-114) -> item readable -> maker delegates their role to the checker and revokes it (CC-004)
# -> the directory feed deactivates a throw-away leaver (SEC-007) -> a manager reviews and revokes access (SEC-007)
# -> a service is defined and disabled per region and channel through catalogue maker-checker (CC-020, CC-026)
# -> a transaction of that service is opened, refused as a duplicate and submitted (CC-021..CC-023, CC-027).
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
perms=$(curl -sf -H "Authorization: Bearer $MAKER" "$IAM/v1/me/authorisation" | json_field "['grants']")
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

echo "7. Grants carry approval limits (CC-003); the maker delegates their role to the checker for an hour (CC-004)"
ADMIN_TOKEN=$(token dev.iam-admin "$DEV_IAM_ADMIN_PASSWORD")
sub() { printf '%s' "$1" | "$PY" -c "import base64,json,sys; p=sys.stdin.read().split('.')[1]; print(json.loads(base64.urlsafe_b64decode(p+'='*(-len(p)%4)))['sub'])"; }
CHECKER_SUB=$(sub "$CHECKER")
has_limits=$(curl -sf -H "Authorization: Bearer $MAKER" "$IAM/v1/me/authorisation" | json_field "['grants'][0].__contains__('approvalLimits')")
[[ "$has_limits" == True ]] || { echo "FAIL: grants have no approvalLimits field"; exit 1; }
# The maker finds their own assignment id without any IAM permission (GET /v1/me/assignments).
source_id=$(curl -sf -H "Authorization: Bearer $MAKER" "$IAM/v1/me/assignments" | "$PY" -c "import json,sys; \
print([a['id'] for a in json.load(sys.stdin)['items'] if a['roleCode']=='REFDATA_MAKER' and a['source']=='DIRECT'][0])")
until=$("$PY" -c "import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
delegation_id=$(curl -sf -H "Authorization: Bearer $MAKER" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/delegations" \
  -d "{\"sourceAssignmentId\":\"$source_id\",\"delegateSubjectId\":\"$CHECKER_SUB\",\"validTo\":\"$until\",\"reason\":\"smoke test cover\"}" \
  | json_field "['id']")
roles=$(curl -sf -H "Authorization: Bearer $CHECKER" "$IAM/v1/me/authorisation" | "$PY" -c "import json,sys; print(sorted(g['roleCode'] for g in json.load(sys.stdin)['grants']))")
[[ "$roles" == *REFDATA_MAKER* ]] || { echo "FAIL: delegate did not get the role: $roles"; exit 1; }

echo "8. The maker revokes the delegation; the checker loses the delegated role"
curl -sf -o /dev/null -H "Authorization: Bearer $MAKER" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/delegations/$delegation_id/revocations" -d '{"reason":"smoke test done"}'
roles=$(curl -sf -H "Authorization: Bearer $CHECKER" "$IAM/v1/me/authorisation" | "$PY" -c "import json,sys; print(sorted(g['roleCode'] for g in json.load(sys.stdin)['grants']))")
[[ "$roles" != *REFDATA_MAKER* ]] || { echo "FAIL: delegate still holds the role: $roles"; exit 1; }

echo "9. The directory feed (client credentials) deactivates a throw-away leaver; a repeat call revokes nothing (SEC-007)"
FEED=$(curl -sf "$TOKEN_URL" -d grant_type=client_credentials -d client_id=itms-directory-feed \
  --data-urlencode "client_secret=$DEV_DIRECTORY_FEED_SECRET" \
  | "$PY" -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
LEAVER="smoke-leaver-$(date +%s)"
curl -sf -o /dev/null -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/role-assignments" \
  -d "{\"subjectId\":\"$LEAVER\",\"roleCode\":\"REFDATA_MAKER\",\"scopes\":{\"regions\":[\"*\"],\"centres\":[\"*\"],\"modules\":[\"*\"]},\"reason\":\"smoke test leaver\"}"
deactivate() {
  curl -sf -H "Authorization: Bearer $FEED" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    "$IAM/v1/subjects/$LEAVER/deactivations" -d '{"reason":"smoke test: left the Authority"}' | json_field "['revokedCount']"
}
count=$(deactivate)
[[ "$count" == 1 ]] || { echo "FAIL: expected 1 revocation, got $count"; exit 1; }
count=$(deactivate)
[[ "$count" == 0 ]] || { echo "FAIL: repeat call revoked $count"; exit 1; }
active=$(curl -sf -H "Authorization: Bearer $FEED" "$IAM/v1/role-assignments?subjectId=$LEAVER&status=ACTIVE" | json_field "['items'].__len__()")
[[ "$active" == 0 ]] || { echo "FAIL: leaver still has $active active assignments"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $FEED" "$IAM/v1/roles")
[[ "$status" == 403 ]] || { echo "FAIL: the feed should not read roles, got $status"; exit 1; }

echo "10. Quarterly access review (SEC-007): admin opens a review over a throw-away region; the checker (reviewer) revokes"
# A fresh region code keeps the review to this run's assignment. If this step fails midway it leaves an OPEN review
# over the throw-away region, which is harmless.
REGION="SMK$(date +%s)"
REVIEWED="smoke-reviewed-$(date +%s)"
curl -sf -o /dev/null -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/role-assignments" \
  -d "{\"subjectId\":\"$REVIEWED\",\"roleCode\":\"REFDATA_MAKER\",\"scopes\":{\"regions\":[\"$REGION\"],\"centres\":[\"*\"],\"modules\":[\"*\"]},\"reason\":\"smoke test reviewed access\"}"
due=$("$PY" -c "import datetime as d; print((d.datetime.now(d.timezone.utc)+d.timedelta(days=7)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
review=$(curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/access-reviews" \
  -d "{\"title\":\"Smoke access review\",\"scopes\":{\"regions\":[\"$REGION\"],\"centres\":[\"*\"],\"modules\":[\"*\"]},\"dueAt\":\"$due\",\"reason\":\"smoke test review\"}")
review_id=$(printf '%s' "$review" | json_field "['id']")
total=$(printf '%s' "$review" | json_field "['counts']['total']")
[[ "$total" == 1 ]] || { echo "FAIL: expected 1 review item, got $total"; exit 1; }
item_id=$(curl -sf -H "Authorization: Bearer $CHECKER" "$IAM/v1/access-review-items?campaignId=$review_id" | json_field "['items'][0]['id']")
decide_item() {
  curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $CHECKER" -H 'Content-Type: application/json' \
    -H 'X-Channel: BACKOFFICE' "$IAM/v1/access-review-items/$item_id/decisions" \
    -d '{"decision":"REVOKE","reason":"smoke test: access no longer needed"}'
}
status=$(decide_item)
[[ "$status" == 200 ]] || { echo "FAIL: reviewer decision returned $status"; exit 1; }
status=$(decide_item)
[[ "$status" == 409 ]] || { echo "FAIL: a second decision should be 409, got $status"; exit 1; }
active=$(curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" "$IAM/v1/role-assignments?subjectId=$REVIEWED&status=ACTIVE" | json_field "['items'].__len__()")
[[ "$active" == 0 ]] || { echo "FAIL: the revoked assignment is still active"; exit 1; }
revoked=$(curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
  "$IAM/v1/access-reviews/$review_id/closures" -d '{"reason":"smoke test review done"}' | json_field "['counts']['revoked']")
[[ "$revoked" == 1 ]] || { echo "FAIL: expected 1 revoked item after closure, got $revoked"; exit 1; }
period=$(curl -sf -H "Authorization: Bearer $ADMIN_TOKEN" "$IAM/v1/access-reviews/overdue-assignments" | json_field "['reviewPeriod']")
[[ -n "$period" ]] || { echo "FAIL: the overdue listing has no review period"; exit 1; }
echo "   review period: $period"

echo "11. Service catalogue (CC-020, CC-026, CC-114): maker defines a service, checker approves, then disables it on the portal in one region"
# A fresh service code per run; a failed run leaves at most one pending change for that throw-away code (harmless).
TXN=http://localhost:8083
SVC="SMK_$(date +%s)"
post_txn() { # token path body -> prints "<status> <body>"
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    "$TXN$2" -d "$3"
}
status_of() { printf '%s' "$1" | tail -n 1; }
body_of() { printf '%s' "$1" | sed '$d'; }
spec="{\"code\":\"$SVC\",\"name\":{\"nl\":\"خدمة تجريبية\",\"en\":\"Smoke test service\"},\"module\":\"VEHICLE\",\"customerTypes\":[\"INDIVIDUAL\"],\"channels\":[\"PORTAL\",\"BACKOFFICE\"],\"requiredDocuments\":[],\"sla\":\"P5D\",\"regions\":[\"*\"],\"anyRegion\":true,\"allowsConcurrentOpen\":false}"
res=$(post_txn "$MAKER" /v1/catalogue-changes "{\"changeType\":\"CREATE_SERVICE\",\"serviceCode\":\"$SVC\",\"service\":$spec,\"reason\":\"smoke test service\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: catalogue change returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
change=$(body_of "$res" | json_field "['id']")
res=$(post_txn "$MAKER" "/v1/catalogue-changes/$change/approvals" "")
[[ "$(status_of "$res")" == 403 ]] || { echo "FAIL: the maker should not approve, got $(status_of "$res")"; exit 1; }
res=$(post_txn "$CHECKER" "/v1/catalogue-changes/$change/approvals" "")
[[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: checker approval returned $(status_of "$res")"; exit 1; }
version=$(curl -sf -H "Authorization: Bearer $MAKER" "$TXN/v1/services/$SVC" | json_field "['version']")
[[ "$version" == 1 ]] || { echo "FAIL: expected version 1, got $version"; exit 1; }
today=$(date -u +%Y-%m-%d)
res=$(post_txn "$MAKER" /v1/catalogue-changes "{\"changeType\":\"ADD_AVAILABILITY_RULE\",\"serviceCode\":\"$SVC\",\"rule\":{\"region\":\"R1\",\"channel\":\"PORTAL\",\"enabled\":false,\"effectiveFrom\":\"$today\"},\"reason\":\"smoke test rule\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: rule change returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
change=$(body_of "$res" | json_field "['id']")
res=$(post_txn "$CHECKER" "/v1/catalogue-changes/$change/approvals" "")
[[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: rule approval returned $(status_of "$res")"; exit 1; }
off=$(curl -sf -H "Authorization: Bearer $MAKER" "$TXN/v1/services/$SVC/availability?region=R1&channel=PORTAL" | json_field "['reason']")
on=$(curl -sf -H "Authorization: Bearer $MAKER" "$TXN/v1/services/$SVC/availability?region=R2&channel=PORTAL" | json_field "['reason']")
[[ "$off" == DISABLED_BY_RULE && "$on" == OFFERED ]] || { echo "FAIL: availability R1=$off R2=$on"; exit 1; }
echo "   $SVC: R1/PORTAL $off, R2/PORTAL $on"

echo "12. Transactions (CC-021, CC-022, CC-023, CC-027): the officer opens one, a duplicate is refused, submission skips payment"
# Uses the fresh service of step 11 (no fee, any region, one open transaction per subject); nothing to clean up.
year=$(date -u +%Y)
new_txn="{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"SMKVIN-$(date +%s)\"},\"applicantFileNumber\":\"TF-SMOKE\",\"homeRegion\":\"R1\"}"
res=$(post_txn "$CHECKER" /v1/transactions "$new_txn")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: opening a transaction returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
ref=$(body_of "$res" | json_field "['reference']")
[[ "$ref" == "$SVC-R1-$year-0000001" ]] || { echo "FAIL: unexpected reference $ref"; exit 1; }
res=$(post_txn "$CHECKER" /v1/transactions "$new_txn")
[[ "$(status_of "$res")" == 409 ]] || { echo "FAIL: a duplicate should be refused, got $(status_of "$res")"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $MAKER" "$TXN/v1/transactions/$ref")
[[ "$status" == 403 ]] || { echo "FAIL: the maker has no transaction permission, expected 403, got $status"; exit 1; }
res=$(post_txn "$CHECKER" "/v1/transactions/$ref/submissions" "")
[[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: submission returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
state=$(body_of "$res" | json_field "['status']")
reasons=$(curl -sf -H "Authorization: Bearer $CHECKER" "$TXN/v1/transactions/$ref/status-history" \
  | "$PY" -c "import json,sys; print(','.join(i['reason'] for i in json.load(sys.stdin)['items']))")
[[ "$state" == IN_REVIEW && "$reasons" == CREATED,SUBMITTED,ZERO_FEE_PAYMENT_SKIPPED ]] \
  || { echo "FAIL: status $state, history $reasons"; exit 1; }
echo "   $ref: $state ($reasons)"

echo "SMOKE OK"
