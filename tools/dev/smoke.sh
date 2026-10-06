#!/usr/bin/env bash
# End-to-end smoke test against the running local stack, with real Keycloak tokens:
# grants come from iam-service, not the token (ADR-022) -> maker submits a change -> maker cannot approve ->
# checker approves (CC-114) -> item readable -> maker delegates their role to the checker and revokes it (CC-004)
# -> the directory feed deactivates a throw-away leaver (SEC-007) -> a manager reviews and revokes access (SEC-007)
# -> a service is defined and disabled per region and channel through catalogue maker-checker (CC-020, CC-026)
# -> a transaction of that service is opened, refused as a duplicate and submitted (CC-021..CC-023, CC-027)
# -> it is found as the newest of its applicant's transactions (CC-025) -> a retry with the same Idempotency-Key is
# replayed, not repeated (ADR-032) -> a hold is placed on a vehicle and lifted by its role (CC-030) -> holds block a
# submission with the customer's reasons and next steps, sensitive ones masked, until lifted (CC-032) -> a supervisor
# overrides a hold for one transaction, which then goes through (CC-034) -> Prometheus scrapes every service, a
# submission's trace crosses transaction-service and hold-eligibility-service, and every service's logs reach Loki
# (NFR-040) -> no ITMS container holds a password or secret in its environment; they read secret files (SEC-004)
# -> services use short-lived OpenBao logins, not the database superuser; a rotated login is picked up without a
# restart and the old one can be dropped (SEC-004, ADR-039).
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
new_key() { printf 'smk-%s-%s' "$(date +%s%N)" "$RANDOM"; }
post_txn() { # token path body [idempotency-key] -> prints "<status> <body>"; a new key per call unless one is given
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    -H "Idempotency-Key: ${4:-$(new_key)}" "$TXN$2" -d "$3"
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

echo "13. Tracking (CC-025): the newest of the applicant's transactions is the one just submitted"
# TF-SMOKE collects one transaction per run; limit=1 keeps the check to this run's (the newest).
newest=$(curl -sf -H "Authorization: Bearer $CHECKER" "$TXN/v1/transactions?applicantFileNumber=TF-SMOKE&limit=1" \
  | "$PY" -c "import json,sys; i=json.load(sys.stdin)['items'][0]; print(i['reference'], i['status'])")
[[ "$newest" == "$ref IN_REVIEW" ]] || { echo "FAIL: newest transaction of TF-SMOKE is $newest"; exit 1; }
echo "   TF-SMOKE: $newest"

echo "14. Draft expiry (CC-024): the job is the only SYSTEM actor; a request through X-Channel SYSTEM is refused"
# Expiry itself needs a 30-day-old Draft, so it is proven by TransactionApiIT.draftExpiry; here only the channel rule.
sys_txn="{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"SMKSYS-$(date +%s)\"},\"applicantFileNumber\":\"TF-SMOKE\",\"homeRegion\":\"R1\"}"
res=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $CHECKER" -H 'Content-Type: application/json' \
  -H 'X-Channel: SYSTEM' -H "Idempotency-Key: $(new_key)" "$TXN/v1/transactions" -d "$sys_txn")
[[ "$(status_of "$res")" == 422 && "$(body_of "$res" | json_field "['code']")" == CHANNEL_REQUIRED ]] \
  || { echo "FAIL: X-Channel SYSTEM should be refused with 422 CHANNEL_REQUIRED, got $(status_of "$res"): $(body_of "$res")"; exit 1; }
echo "   X-Channel SYSTEM: 422 CHANNEL_REQUIRED"

echo "15. Idempotency-Key (ADR-032): a retry with the same key replays the response; no key is refused"
idem_txn="{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"SMKIDM-$(date +%s)\"},\"applicantFileNumber\":\"TF-SMOKE-IDEM\",\"homeRegion\":\"R1\"}"
key=$(new_key)
res=$(post_txn "$CHECKER" /v1/transactions "$idem_txn" "$key")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: opening with a key returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
first=$(body_of "$res" | json_field "['reference']")
replayed=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $CHECKER" -H 'Content-Type: application/json' \
  -H 'X-Channel: BACKOFFICE' -H "Idempotency-Key: $key" "$TXN/v1/transactions" -d "$idem_txn" \
  | tr -d '\r' | awk 'tolower($1) == "idempotent-replayed:" {print $2}')
res=$(post_txn "$CHECKER" /v1/transactions "$idem_txn" "$key")
[[ "$replayed" == true && "$(status_of "$res")" == 201 && "$(body_of "$res" | json_field "['reference']")" == "$first" ]] \
  || { echo "FAIL: retry should replay $first (Idempotent-Replayed=$replayed), got $(status_of "$res"): $(body_of "$res")"; exit 1; }
res=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $CHECKER" -H 'Content-Type: application/json' \
  -H 'X-Channel: BACKOFFICE' "$TXN/v1/transactions/$first/cancellations" -d '{"reason":"smoke without key"}')
[[ "$(status_of "$res")" == 400 && "$(body_of "$res" | json_field "['code']")" == IDEMPOTENCY_KEY_REQUIRED ]] \
  || { echo "FAIL: no key should be refused with 400 IDEMPOTENCY_KEY_REQUIRED, got $(status_of "$res"): $(body_of "$res")"; exit 1; }
echo "   $first replayed; no key: 400 IDEMPOTENCY_KEY_REQUIRED"

echo "16. Holds (CC-030): the officer places a hold on a vehicle; the maker cannot place one; the officer lifts it"
# A throw-away vehicle per run; the hold ends lifted, so nothing is left in force.
HOLDS=http://localhost:8084
post_hold() { # token path body -> prints "<status> <body>"
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    -H "Idempotency-Key: $(new_key)" "$HOLDS$2" -d "$3"
}
hold_vin="SMKHLD-$(date +%s)"
res=$(post_hold "$CHECKER" /v1/holds "{\"subject\":{\"type\":\"VEHICLE\",\"id\":\"$hold_vin\"},\"holdType\":\"UNPAID_FINES\",\"module\":\"VIOLATIONS\",\"source\":\"VIOLATIONS\",\"reason\":\"smoke test hold\",\"blockedServices\":[\"*\"],\"liftableByRoles\":[\"HOLD_OFFICER\"]}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: placing a hold returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
hold_id=$(body_of "$res" | json_field "['id']")
# dev.maker is the hold supervisor (CC-034, step 18): it reads and overrides holds but holds no hold.place.
res=$(post_hold "$MAKER" /v1/holds "{\"subject\":{\"type\":\"VEHICLE\",\"id\":\"$hold_vin\"},\"holdType\":\"UNPAID_FINES\",\"module\":\"VIOLATIONS\",\"source\":\"VIOLATIONS\",\"reason\":\"smoke test hold\",\"blockedServices\":[\"*\"],\"liftableByRoles\":[\"HOLD_OFFICER\"]}")
[[ "$(status_of "$res")" == 403 ]] || { echo "FAIL: the maker has no hold.place, expected 403, got $(status_of "$res")"; exit 1; }
res=$(post_hold "$CHECKER" "/v1/holds/$hold_id/lifts" '{"reason":"smoke test lift"}')
[[ "$(status_of "$res")" == 200 && "$(body_of "$res" | json_field "['status']")" == LIFTED ]] \
  || { echo "FAIL: lifting returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
versions=$(curl -sf -H "Authorization: Bearer $CHECKER" "$HOLDS/v1/holds/$hold_id/history" | json_field "['items'].__len__()")
[[ "$versions" == 2 ]] || { echo "FAIL: expected 2 versions, got $versions"; exit 1; }
echo "   $hold_vin: hold $hold_id placed and lifted (2 versions)"

echo "17. Eligibility (CC-032): holds block a submission with reasons and next steps (a police circular masked); lifted, it goes through"
# transaction-service asks hold-eligibility-service with its own service account (ELIGIBILITY_CHECKER). A throw-away
# vehicle per run; both holds end lifted and the transaction ends In review, so nothing is left in force or open.
elig_vin="SMKELG-$(date +%s)"
place_on() { # holdType -> hold id
  local r
  r=$(post_hold "$CHECKER" /v1/holds "{\"subject\":{\"type\":\"VEHICLE\",\"id\":\"$elig_vin\"},\"holdType\":\"$1\",\"module\":\"VIOLATIONS\",\"source\":\"VIOLATIONS\",\"reason\":\"smoke wanted case $1\",\"blockedServices\":[\"$SVC\"],\"liftableByRoles\":[\"HOLD_OFFICER\"]}")
  [[ "$(status_of "$r")" == 201 ]] || { echo "FAIL: placing $1 returned $(status_of "$r"): $(body_of "$r")" >&2; exit 1; }
  body_of "$r" | json_field "['id']"
}
fines_id=$(place_on UNPAID_FINES)
circular_id=$(place_on POLICE_CIRCULAR)
res=$(post_txn "$CHECKER" /v1/transactions "{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"$elig_vin\"},\"applicantFileNumber\":\"TF-SMOKE-ELIG\",\"homeRegion\":\"R1\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: opening returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
elig_ref=$(body_of "$res" | json_field "['reference']")
res=$(post_txn "$CHECKER" "/v1/transactions/$elig_ref/submissions" "")
blocked=$(body_of "$res" | "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d.get('code'), ','.join(sorted(b['reasonCode'] + '/' + b['nextStep'] for b in d.get('blocks', []))))")
[[ "$(status_of "$res")" == 422 && "$blocked" == "ELIGIBILITY_BLOCKED RESTRICTED/CONTACT_AUTHORITY,UNPAID_FINES/PAY_FINES" ]] \
  || { echo "FAIL: submission should be blocked, got $(status_of "$res"): $(body_of "$res")"; exit 1; }
if body_of "$res" | grep -qi -e wanted -e POLICE; then echo "FAIL: hold details reached the customer: $(body_of "$res")"; exit 1; fi
for id in "$fines_id" "$circular_id"; do
  res=$(post_hold "$CHECKER" "/v1/holds/$id/lifts" '{"reason":"smoke test lift"}')
  [[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: lifting $id returned $(status_of "$res")"; exit 1; }
done
res=$(post_txn "$CHECKER" "/v1/transactions/$elig_ref/submissions" "")
[[ "$(status_of "$res")" == 200 && "$(body_of "$res" | json_field "['status']")" == IN_REVIEW ]] \
  || { echo "FAIL: submission after lifting returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
echo "   $elig_ref: blocked ($blocked), then submitted once the holds were lifted"

echo "18. Override (CC-034): a supervisor overrides a hold for one transaction, with a reason and document; it goes through"
# dev.checker (HOLD_OFFICER) places the hold; dev.maker (HOLD_SUPERVISOR) overrides it, so the placer never does. A
# throw-away vehicle per run; the hold ends lifted and the transaction In review.
elig_vin="SMKOVR-$(date +%s)"
ovr_hold=$(place_on COURT_ORDER)
res=$(post_txn "$CHECKER" /v1/transactions "{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"$elig_vin\"},\"applicantFileNumber\":\"TF-SMOKE-OVR\",\"homeRegion\":\"R1\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: opening returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
ovr_ref=$(body_of "$res" | json_field "['reference']")
res=$(post_txn "$CHECKER" "/v1/transactions/$ovr_ref/submissions" "")
[[ "$(status_of "$res")" == 422 ]] || { echo "FAIL: submission should be blocked, got $(status_of "$res"): $(body_of "$res")"; exit 1; }
override_body="{\"transactionReference\":\"$ovr_ref\",\"reason\":\"smoke court ruling\",\"supportingDocument\":{\"type\":\"COURT_RULING\",\"number\":\"SMOKE-CR-1\"}}"
res=$(post_hold "$CHECKER" "/v1/holds/$ovr_hold/overrides" "$override_body")
[[ "$(status_of "$res")" == 403 ]] || { echo "FAIL: the officer has no hold.override, expected 403, got $(status_of "$res")"; exit 1; }
res=$(post_hold "$MAKER" "/v1/holds/$ovr_hold/overrides" "$override_body")
[[ "$(status_of "$res")" == 201 && "$(body_of "$res" | json_field "['transactionReference']")" == "$ovr_ref" ]] \
  || { echo "FAIL: override returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
res=$(post_txn "$CHECKER" "/v1/transactions/$ovr_ref/submissions" "")
[[ "$(status_of "$res")" == 200 && "$(body_of "$res" | json_field "['status']")" == IN_REVIEW ]] \
  || { echo "FAIL: submission after the override returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
still=$(curl -sf -H "Authorization: Bearer $CHECKER" "$HOLDS/v1/holds/$ovr_hold" | json_field "['status']")
[[ "$still" == ACTIVE ]] || { echo "FAIL: the overridden hold should stay ACTIVE, got $still"; exit 1; }
res=$(post_hold "$CHECKER" "/v1/holds/$ovr_hold/lifts" '{"reason":"smoke test lift"}')
[[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: lifting $ovr_hold returned $(status_of "$res")"; exit 1; }
echo "   $ovr_ref: blocked, overridden by the supervisor for this transaction only, then submitted"

echo "19. Observability (NFR-040): metrics scraped, one trace across services, logs collected centrally"
GRAFANA=http://localhost:3000
grafana() { # data-source-uid/path [curl options...]: a data source's API through Grafana (Tempo and Loki are not published)
  local path=$1; shift
  curl -sf -u "admin:$GRAFANA_ADMIN_PASSWORD" "$@" "$GRAFANA/api/datasources/proxy/uid/$path"
}
up=$(curl -sf --get "http://localhost:9090/api/v1/query" --data-urlencode 'query=up{job="itms-services"}' \
  | "$PY" -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(sorted(x['metric']['instance'].split(':')[0] for x in r if x['value'][1]=='1'))")
[[ "$up" == "['hold-eligibility-service', 'iam-service', 'reference-data-service', 'transaction-service']" ]] \
  || { echo "FAIL: Prometheus should scrape all four services, up: $up"; exit 1; }
trace_id=$("$PY" -c "import secrets; print(secrets.token_hex(16))")
res=$(post_txn "$CHECKER" /v1/transactions "{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"VEHICLE\",\"id\":\"SMKOBS-$(date +%s)\"},\"applicantFileNumber\":\"TF-SMOKE-OBS\",\"homeRegion\":\"R1\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: opening returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
obs_ref=$(body_of "$res" | json_field "['reference']")
status=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $CHECKER" -H 'X-Channel: BACKOFFICE' \
  -H "Idempotency-Key: $(new_key)" -H "traceparent: 00-$trace_id-00f067aa0ba902b7-01" "$TXN/v1/transactions/$obs_ref/submissions")
[[ "$status" == 200 ]] || { echo "FAIL: traced submission returned $status"; exit 1; }
services=""
for _ in $(seq 1 30); do # spans are exported in batches every few seconds
  services=$(grafana "tempo/api/traces/$trace_id" 2>/dev/null | "$PY" -c "
import json,sys
d=json.load(sys.stdin)
names=set()
for b in d.get('batches', d.get('resourceSpans', [])):
    for a in b['resource']['attributes']:
        if a['key']=='service.name': names.add(a['value']['stringValue'])
print(sorted(names))" 2>/dev/null || true)
  [[ "$services" == *hold-eligibility-service* && "$services" == *transaction-service* ]] && break
  sleep 2
done
[[ "$services" == *hold-eligibility-service* && "$services" == *transaction-service* ]] \
  || { echo "FAIL: trace $trace_id should cross transaction-service and hold-eligibility-service, got: $services"; exit 1; }
logged=$(grafana "loki/loki/api/v1/query" --get --data-urlencode 'query=sum by (service_name) (count_over_time({service_name=~".+"}[6h]))' \
  | "$PY" -c "import json,sys; print(sorted(x['metric']['service_name'] for x in json.load(sys.stdin)['data']['result']))")
for svc in iam-service reference-data-service transaction-service hold-eligibility-service; do
  [[ "$logged" == *"'$svc'"* ]] || { echo "FAIL: no logs from $svc in Loki: $logged"; exit 1; }
done
echo "   trace $trace_id: $services; logs from $logged"

echo "20. Secrets (SEC-004): no service or database container has a password or secret in its environment"
for c in iam-service reference-data-service transaction-service hold-eligibility-service postgres iam-postgres \
         transaction-postgres hold-postgres grafana bao-agent; do
  id=$(docker compose -f infra/local/compose.yaml --env-file infra/local/.env ps -q "$c")
  leaked=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" \
    | grep -iE '^[^=]*(PASSWORD|SECRET)[^=]*=' | grep -vE '^[^=]*_FILE=' | cut -d= -f1 || true)
  [[ -z "$leaked" ]] || { echo "FAIL: $c has secrets in its environment: $leaked"; exit 1; }
done
# The client secret is a compose secret; the database login comes from OpenBao (spring/datasource/, ADR-039).
MSYS_NO_PATHCONV=1 docker compose -f infra/local/compose.yaml --env-file infra/local/.env exec -T transaction-service \
  sh -c 'test -s /run/secrets/itms.clients.eligibility.client-secret && test -s /run/secrets/spring/datasource/username \
         && test -s /run/secrets/spring/datasource/password' || { echo "FAIL: transaction-service secret files missing"; exit 1; }
echo "   10 containers checked; services read their secrets from /run/secrets"

echo "21. Rotated database logins (SEC-004, ADR-039): short-lived, not superuser; a new login is used without a restart"
DC=(docker compose -f infra/local/compose.yaml --env-file infra/local/.env)
iam_sql() { "${DC[@]}" exec -T iam-postgres psql -qtA -U "$IAM_DB_USER" -d iam -c "$1"; }
service_logins() { iam_sql "select string_agg(distinct usename, ',') from pg_stat_activity where usename like 'v-%'"; }
svc_users=$(iam_sql "select count(*) from pg_stat_activity where usename = '$IAM_DB_USER' and backend_type = 'client backend' and pid <> pg_backend_pid() and application_name <> 'vault'") # OpenBao's own admin session is expected
[[ "$svc_users" == 0 ]] || { echo "FAIL: $svc_users connections still use the superuser $IAM_DB_USER"; exit 1; }
old=$(service_logins)
[[ -n "$old" && "$old" != *,* ]] || { echo "FAIL: expected one OpenBao login on iam, got '$old'"; exit 1; }
flags=$(iam_sql "select rolsuper::text || ',' || pg_has_role('$old', 'itms_owner', 'member')::text || ',' || (rolvaliduntil is not null)::text from pg_roles where rolname = '$old'")
[[ "$flags" == "false,true,true" ]] || { echo "FAIL: login $old should be non-superuser, itms_owner member, expiring; got $flags"; exit 1; }
owner=$(iam_sql "select pg_get_userbyid(relowner) from pg_class where relname = 'flyway_schema_history'")
[[ "$owner" == itms_owner ]] || { echo "FAIL: flyway_schema_history is owned by $owner"; exit 1; }
"${DC[@]}" restart bao-agent >/dev/null # a fresh agent issues new logins, as on lease renewal at the maximum TTL
new="$old"
for _ in $(seq 1 45); do
  new=$(service_logins)
  [[ "$new" != *"$old"* && -n "$new" ]] && break
  sleep 2
done
[[ "$new" != *"$old"* && -n "$new" ]] || { echo "FAIL: iam-service still connects as $old (now: $new)"; exit 1; }
# The old login ends, as when its lease expires: no session of it is left to cut, and dropping it changes nothing.
iam_sql "select pg_terminate_backend(pid) from pg_stat_activity where usename = '$old'" >/dev/null
iam_sql "drop role if exists \"$old\"" >/dev/null
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $MAKER" "$IAM/v1/me/assignments")
[[ "$status" == 200 ]] || { echo "FAIL: iam-service after rotation returned $status"; exit 1; }
echo "   iam-service: $old -> $new (no restart); old login dropped; API still answers"

echo "22. API gateway (INT-001) and audit events on the broker (INT-005, CC-120, ADR-040)"
GW=http://localhost:9080
GW_TOKEN=$(token dev.iam-admin "$DEV_IAM_ADMIN_PASSWORD") # fresh: earlier tokens may have expired
status=$(curl -s -o /dev/null -w '%{http_code}' "$GW/v1/roles")
[[ "$status" == 401 ]] || { echo "FAIL: gateway without a token returned $status, expected 401"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer not-a-token" "$GW/v1/roles")
[[ "$status" == 401 ]] || { echo "FAIL: gateway with a forged token returned $status, expected 401"; exit 1; }
# Routed to the owning service: the administrator may read roles; for the other services the service itself answers
# (200, or 403 from its own authorisation), never the gateway's 404.
for path in /v1/roles /v1/me/assignments /v1/reference-lists /v1/services /v1/holds; do
  status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $GW_TOKEN" "$GW$path")
  [[ "$status" == 200 || ( "$status" == 403 && "$path" != /v1/roles && "$path" != /v1/me/* ) ]] \
    || { echo "FAIL: gateway $path returned $status"; exit 1; }
done
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $GW_TOKEN" "$GW/actuator/health")
[[ "$status" == 404 ]] || { echo "FAIL: gateway exposes /actuator ($status)"; exit 1; }
cid=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $GW_TOKEN" "$GW/v1/roles" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-correlation-id"{print $2}')
[[ -n "$cid" ]] || { echo "FAIL: gateway did not return X-Correlation-Id"; exit 1; }
limit=$(curl -s -D - -o /dev/null -H "Authorization: Bearer $GW_TOKEN" "$GW/v1/roles" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-ratelimit-limit"{print $2}')
[[ "$limit" == 600 ]] || { echo "FAIL: rate limit header missing or wrong ('$limit')"; exit 1; }
# Earlier steps wrote audit records; the relay must have sent them to itms.audit.v1 as CloudEvents.
unpublished=1
for _ in $(seq 1 30); do
  unpublished=$(iam_sql "select count(*) from audit_outbox where published_at is null")
  [[ "$unpublished" == 0 ]] && break
  sleep 1
done
[[ "$unpublished" == 0 ]] || { echo "FAIL: $unpublished iam audit records not relayed"; exit 1; }
first=$(MSYS_NO_PATHCONV=1 "${DC[@]}" exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic itms.audit.v1 --from-beginning --max-messages 1 --timeout-ms 20000 2>/dev/null)
printf '%s' "$first" | "$PY" -c "import json,sys; e=json.load(sys.stdin); assert e['specversion']=='1.0' and e['type']=='itms.audit.recorded' and e['data']['action'], e" \
  || { echo "FAIL: itms.audit.v1 does not hold CloudEvents audit records"; exit 1; }
echo "   gateway: 401 without or with a forged token, routed to all four services, no /actuator, correlation id and rate limit set"
echo "   broker: every iam audit record relayed; itms.audit.v1 holds CloudEvents"

echo "SMOKE OK"
