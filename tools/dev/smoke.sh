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
# restart and the old one can be dropped (SEC-004, ADR-039) -> ... -> rules, workflow, notifications, documents and
# certificates, appointments, the central audit trail, payments (signed callbacks), vehicles (UAT-3 check) and
# violations (UAT-1 suspension, UAT-4 duplicate callback) run end to end (steps 29-37).
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source infra/local/.env; set +a
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python
# Same value as for up.sh. The light profile (ADR-057) has no Tempo, Loki or Grafana and a clamd stand-in.
LOCAL_PROFILE=${ITMS_LOCAL_PROFILE:-full}
COMPOSE_FILES=(-f infra/local/compose.yaml)
[[ "$LOCAL_PROFILE" == lite ]] && COMPOSE_FILES+=(-f infra/local/compose.lite.yaml)
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
expected_up=$(sed -n '/job_name: itms-services/,/job_name:/s/^ *- \([a-z-]*\):8090$/\1/p' infra/local/observability/prometheus.yaml \
  | "$PY" -c "import sys; print(sorted(l.strip() for l in sys.stdin if l.strip()))")
[[ "$up" == "$expected_up" ]] || { echo "FAIL: Prometheus should scrape every service $expected_up, up: $up"; exit 1; }
if [[ "$LOCAL_PROFILE" == lite ]]; then
  echo "   every service scraped; traces and logs SKIPPED (the light profile has no Tempo or Loki, ADR-057)"
else
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
fi

echo "20. Secrets (SEC-004): no service or database container has a password or secret in its environment"
for c in iam-service reference-data-service transaction-service hold-eligibility-service postgres iam-postgres \
         transaction-postgres hold-postgres grafana bao-agent; do
  [[ "$c" == grafana && "$LOCAL_PROFILE" == lite ]] && continue # not started in the light profile
  id=$(docker compose "${COMPOSE_FILES[@]}" --env-file infra/local/.env ps -q "$c")
  leaked=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$id" \
    | grep -iE '^[^=]*(PASSWORD|SECRET)[^=]*=' | grep -vE '^[^=]*_FILE=' | cut -d= -f1 || true)
  [[ -z "$leaked" ]] || { echo "FAIL: $c has secrets in its environment: $leaked"; exit 1; }
done
# The client secret is a compose secret; the database login comes from OpenBao (spring/datasource/, ADR-039).
MSYS_NO_PATHCONV=1 docker compose "${COMPOSE_FILES[@]}" --env-file infra/local/.env exec -T transaction-service \
  sh -c 'test -s /run/secrets/itms.clients.eligibility.client-secret && test -s /run/secrets/spring/datasource/username \
         && test -s /run/secrets/spring/datasource/password' || { echo "FAIL: transaction-service secret files missing"; exit 1; }
echo "   containers checked; services read their secrets from /run/secrets"

echo "21. Rotated database logins (SEC-004, ADR-039): short-lived, not superuser; a new login is used without a restart"
DC=(docker compose "${COMPOSE_FILES[@]}" --env-file infra/local/.env)
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
for path in /v1/roles /v1/me/assignments /v1/reference-lists /v1/services "/v1/holds?subjectType=VEHICLE&subjectId=SMKGW1"; do
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

echo "23. Traffic files (DL-001, DL-002, DL-005, SEC-014): register identity locked, visitor by passport, masking"
TF=http://localhost:8085
TF_CHECKER=$(token dev.checker "$DEV_CHECKER_PASSWORD")
TF_MAKER=$(token dev.maker "$DEV_MAKER_PASSWORD")
post_tf() { # token path body -> prints "<body>\n<status>"
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    -H "Idempotency-Key: $(new_key)" "$TF$2" -d "$3"
}
# The citizen of the identity-register mock; a second run finds the file opened by the first (409 names it).
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals '{"document":{"type":"NATIONAL_ID","nationalIdNo":"784199012345671"},"category":"INDIVIDUAL","type":"CITIZEN","regionOpened":"DXB","profile":{"mobile":"+971500000001","languages":["AR","EN"]}}')
case "$(status_of "$res")" in
  201) citizen=$(body_of "$res" | json_field "['fileNumber']")
       src=$(body_of "$res" | json_field "['identitySource']")
       [[ "$src" == REGISTER ]] || { echo "FAIL: citizen identity source is $src"; exit 1; } ;;
  409) [[ "$(body_of "$res" | json_field "['code']")" == FILE_ALREADY_EXISTS ]] || { echo "FAIL: $(body_of "$res")"; exit 1; }
       citizen=$(curl -s -H "Authorization: Bearer $TF_CHECKER" -H 'Content-Type: application/json' "$TF/v1/traffic-files/searches" \
         -d '{"document":{"type":"NATIONAL_ID","nationalIdNo":"784199012345671"}}' | json_field "['items'][0]['fileNumber']") ;;
  *) echo "FAIL: opening the citizen's file returned $(status_of "$res"): $(body_of "$res")"; exit 1 ;;
esac
file=$(curl -s -H "Authorization: Bearer $TF_CHECKER" "$TF/v1/traffic-files/$citizen")
masked=$(printf '%s' "$file" | json_field "['identity']['nationalIdNo']")
[[ "$masked" == "***********5671" ]] || { echo "FAIL: officer sees the identity number unmasked or wrong: $masked"; exit 1; }
[[ "$(printf '%s' "$file" | json_field "['identity']['name']['givenEn']")" == Ahmed ]] || { echo "FAIL: register name not used"; exit 1; }
[[ "$(printf '%s' "$file" | json_field "['lockedFields']")" == *birthDate* ]] || { echo "FAIL: register fields not locked"; exit 1; }
full=$(curl -s -H "Authorization: Bearer $TF_MAKER" "$TF/v1/traffic-files/$citizen" | json_field "['identity']['nationalIdNo']")
[[ "$full" == 784199012345671 ]] || { echo "FAIL: the sensitive-data reader does not see the full number ($full)"; exit 1; }
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals '{"document":{"type":"NATIONAL_ID","nationalIdNo":"784199012345671"},"category":"INDIVIDUAL","type":"CITIZEN","regionOpened":"DXB","visitorIdentity":{"name":{"givenEn":"X","familyEn":"Y"},"nationality":"ARE","birthDate":"1990-01-01","gender":"MALE"}}')
[[ "$(status_of "$res")" == 422 ]] || { echo "FAIL: entering register fields should be 422, got $(status_of "$res")"; exit 1; }
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals '{"document":{"type":"NATIONAL_ID","nationalIdNo":"784197012345673"},"category":"INDIVIDUAL","type":"CITIZEN","regionOpened":"DXB"}')
[[ "$(status_of "$res")" == 422 && "$(body_of "$res" | json_field "['code']")" == PERSON_DECEASED ]] || { echo "FAIL: deceased person: $(status_of "$res")"; exit 1; }
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals '{"document":{"type":"NATIONAL_ID","nationalIdNo":"999999999999999"},"category":"INDIVIDUAL","type":"CITIZEN","regionOpened":"DXB"}')
[[ "$(status_of "$res")" == 503 ]] || { echo "FAIL: register down should be 503, got $(status_of "$res")"; exit 1; }
passport="SMK$(date +%s)"
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals "{\"document\":{\"type\":\"PASSPORT\",\"passportNo\":\"$passport\",\"passportCountry\":\"GBR\"},\"category\":\"INDIVIDUAL\",\"type\":\"VISITOR\",\"regionOpened\":\"DXB\",\"visitorIdentity\":{\"name\":{\"givenEn\":\"Jane\",\"familyEn\":\"Smoke\"},\"nationality\":\"GBR\",\"birthDate\":\"1985-05-05\",\"gender\":\"FEMALE\"}}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: visitor file returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
visitor=$(body_of "$res" | json_field "['fileNumber']")
version=$(body_of "$res" | json_field "['version']")
res=$(post_tf "$TF_CHECKER" "/v1/traffic-files/$visitor/profile-updates" "{\"version\":$version,\"profile\":{\"email\":\"jane@example.org\",\"bloodGroup\":\"O_POS\"}}")
[[ "$(status_of "$res")" == 200 && "$(body_of "$res" | json_field "['version']")" == $((version + 1)) ]] || { echo "FAIL: profile update returned $(status_of "$res")"; exit 1; }
[[ "$(body_of "$res" | json_field "['profile']['bloodGroup']")" == None ]] || { echo "FAIL: the officer sees health data"; exit 1; }
res=$(post_tf "$TF_CHECKER" "/v1/traffic-files/$visitor/profile-updates" "{\"version\":$version,\"profile\":{}}")
[[ "$(status_of "$res")" == 409 ]] || { echo "FAIL: a stale version should be 409, got $(status_of "$res")"; exit 1; }
versions=$(curl -s -H "Authorization: Bearer $TF_CHECKER" "$TF/v1/traffic-files/$visitor/history" | json_field "['items'].__len__()")
[[ "$versions" == 2 ]] || { echo "FAIL: expected 2 versions, got $versions"; exit 1; }
echo "   citizen $citizen (register, masked for the officer), visitor $visitor (passport, 2 versions); deceased 422, register down 503"

echo "24. Company files (DL-003, DL-004): licence from the authority mock, signatories, refusals"
# Rapid Transport LLC of the licensing-authority mock; a second run finds the file opened by the first (409 names it).
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/companies "{\"licence\":{\"source\":\"DXB_DED\",\"number\":\"CN-1001234\"},\"category\":\"COMPANY\",\"type\":\"LLC\",\"regionOpened\":\"DXB\",\"signatories\":[\"$visitor\"],\"fleetManagers\":[]}")
case "$(status_of "$res")" in
  201) company=$(body_of "$res" | json_field "['fileNumber']")
       [[ "$(body_of "$res" | json_field "['company']['nameEn']")" == "Rapid Transport LLC" ]] || { echo "FAIL: company name not from the authority"; exit 1; } ;;
  409) company=$(body_of "$res" | json_field "['detail']" | grep -o '[0-9]\{11\}' | head -1) ;;
  *) echo "FAIL: opening the company file returned $(status_of "$res"): $(body_of "$res")"; exit 1 ;;
esac
cversion=$(curl -s -H "Authorization: Bearer $TF_CHECKER" "$TF/v1/traffic-files/$company" | json_field "['version']")
res=$(post_tf "$TF_CHECKER" "/v1/traffic-files/$company/representatives-updates" "{\"version\":$cversion,\"signatories\":[\"$visitor\"],\"fleetManagers\":[\"$citizen\"]}")
[[ "$(status_of "$res")" == 200 ]] || { echo "FAIL: representatives update returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
for case_ in "CN-2000001 COMPANY_LICENCE_EXPIRED" "CN-3000001 COMPANY_LICENCE_CANCELLED" "CN-0000404 COMPANY_LICENCE_NOT_FOUND"; do
  set -- $case_
  res=$(post_tf "$TF_CHECKER" /v1/traffic-files/companies "{\"licence\":{\"source\":\"DXB_DED\",\"number\":\"$1\"},\"category\":\"COMPANY\",\"type\":\"LLC\",\"regionOpened\":\"DXB\",\"signatories\":[\"$visitor\"]}")
  [[ "$(body_of "$res" | json_field "['code']")" == "$2" ]] || { echo "FAIL: licence $1 expected $2, got $(body_of "$res")"; exit 1; }
done
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/companies "{\"licence\":{\"source\":\"DXB_DED\",\"number\":\"CN-1005678\"},\"category\":\"COMPANY\",\"type\":\"LLC\",\"regionOpened\":\"DXB\",\"signatories\":[\"$company\"]}")
[[ "$(body_of "$res" | json_field "['code']")" =~ ^(REPRESENTATIVE_INVALID|FILE_ALREADY_EXISTS)$ ]] || { echo "FAIL: a company as signatory: $(body_of "$res")"; exit 1; }
echo "   company $company (Rapid Transport LLC); representatives replaced; expired, cancelled, unknown licences refused"

echo "25. Ban list (DL-013): a banned visitor cannot get a file until the ban is lifted"
banned="SMB$(date +%s)"
res=$(post_tf "$TF_CHECKER" /v1/ban-entries "{\"person\":{\"type\":\"PASSPORT\",\"passportNo\":\"$banned\",\"passportCountry\":\"FRA\"},\"reason\":\"smoke test ban\",\"source\":\"COURT\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: placing a ban returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
ban_id=$(body_of "$res" | json_field "['id']")
visitor_body="{\"document\":{\"type\":\"PASSPORT\",\"passportNo\":\"$banned\",\"passportCountry\":\"FRA\"},\"category\":\"INDIVIDUAL\",\"type\":\"VISITOR\",\"regionOpened\":\"DXB\",\"visitorIdentity\":{\"name\":{\"givenEn\":\"Ban\",\"familyEn\":\"Smoke\"},\"nationality\":\"FRA\",\"birthDate\":\"1980-01-01\",\"gender\":\"MALE\"}}"
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals "$visitor_body")
[[ "$(body_of "$res" | json_field "['code']")" == PERSON_BANNED ]] || { echo "FAIL: a banned person got a file: $(body_of "$res")"; exit 1; }
res=$(post_tf "$TF_CHECKER" "/v1/ban-entries/$ban_id/lifts" '{"reason":"smoke test lift"}')
[[ "$(body_of "$res" | json_field "['status']")" == LIFTED ]] || { echo "FAIL: lifting the ban: $(body_of "$res")"; exit 1; }
res=$(post_tf "$TF_CHECKER" /v1/traffic-files/individuals "$visitor_body")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: after the lift the file should open, got $(status_of "$res")"; exit 1; }
echo "   ban $ban_id placed, opening refused, lifted, file opened"

echo "26. Duplicates and merge (DL-009): two passports of one person are found and merged by the supervisor"
seed=$(date +%s)
born="19$((60 + seed % 30))-0$((1 + seed % 9))-1$((seed % 9))"
open_visitor() { # passport given
  post_tf "$TF_CHECKER" /v1/traffic-files/individuals "{\"document\":{\"type\":\"PASSPORT\",\"passportNo\":\"$1\",\"passportCountry\":\"IND\"},\"category\":\"INDIVIDUAL\",\"type\":\"VISITOR\",\"regionOpened\":\"DXB\",\"visitorIdentity\":{\"name\":{\"givenEn\":\"$2\",\"familyEn\":\"Smokeduplicate$seed\"},\"nationality\":\"IND\",\"birthDate\":\"$born\",\"gender\":\"MALE\"}}"
}
res=$(open_visitor "SMD${seed}A" Rajesh); first=$(body_of "$res" | json_field "['fileNumber']")
res=$(open_visitor "SMD${seed}B" Rajeshh); second=$(body_of "$res" | json_field "['fileNumber']")
found=$(curl -s -H "Authorization: Bearer $TF_CHECKER" "$TF/v1/traffic-files/$first/probable-duplicates" \
  | "$PY" -c "import json,sys; print(' '.join(i['file']['fileNumber'] for i in json.load(sys.stdin)['items']))")
[[ "$found" == *"$second"* ]] || { echo "FAIL: $second not found as a duplicate of $first ($found)"; exit 1; }
res=$(post_tf "$TF_MAKER" "/v1/traffic-files/$first/merges" "{\"mergedFileNumber\":\"$second\",\"reason\":\"smoke test merge\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: merge returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
status=$(curl -s -H "Authorization: Bearer $TF_CHECKER" "$TF/v1/traffic-files/$second" | json_field "['status']")
[[ "$status" == MERGED ]] || { echo "FAIL: merged file status $status"; exit 1; }
echo "   $second merged into $first"

echo "27. Register notices (DL-010): re-read from the register, handled once per event id"
ev="smoke-$(date +%s)"
notice() { # event-id national-id
  curl -s -H "Authorization: Bearer $TF_MAKER" -H 'Content-Type: application/json' -H 'X-Channel: PARTNER' \
    "$TF/v1/identity-register-notifications" -d "{\"eventId\":\"$1\",\"nationalIdNo\":\"$2\",\"changeType\":\"RESIDENCY\"}"
}
first_outcome=$(notice "$ev" 784199012345671 | json_field "['outcome']")
[[ "$first_outcome" =~ ^(APPLIED|UNCHANGED)$ ]] || { echo "FAIL: first notice outcome $first_outcome"; exit 1; }
[[ "$(notice "$ev" 784199012345671 | json_field "['outcome']")" == DUPLICATE ]] || { echo "FAIL: repeated notice not DUPLICATE"; exit 1; }
[[ "$(notice "$ev-none" 123456780 | json_field "['outcome']")" == NO_FILE ]] || { echo "FAIL: notice without a file"; exit 1; }
echo "   citizen notice $first_outcome, repeat DUPLICATE, unknown person NO_FILE"

echo "28. Fees (CC-040..CC-047): maker submits, checker approves, the customer sees an itemised quote"
FEES=http://localhost:8086
fee_service="SMK$(date +%s | tail -c 7)"
post_fee() { # token path body
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    -H "Idempotency-Key: $(new_key)" "$FEES$2" -d "$3"
}
today=$(date -u +%F)
res=$(post_fee "$TF_MAKER" /v1/fee-versions "{\"feeCode\":\"BASE_$fee_service\",\"serviceCode\":\"$fee_service\",\"nameNl\":\"رسم\",\"nameEn\":\"Base fee\",\"financialItem\":\"REV_SMOKE\",\"beneficiary\":\"AUTHORITY\",\"method\":{\"type\":\"PER_UNIT\",\"unit\":\"YEAR\",\"rate\":\"10000\"},\"effectiveFrom\":\"$today\",\"reason\":\"smoke test fee\"}")
[[ "$(status_of "$res")" == 201 ]] || { echo "FAIL: submitting a fee returned $(status_of "$res"): $(body_of "$res")"; exit 1; }
fee_id=$(body_of "$res" | json_field "['id']")
res=$(post_fee "$TF_MAKER" "/v1/fee-versions/$fee_id/approvals" '{}')
[[ "$(status_of "$res")" == 403 ]] || { echo "FAIL: the maker approved their own fee ($(status_of "$res"))"; exit 1; }
res=$(post_fee "$TF_CHECKER" "/v1/fee-versions/$fee_id/approvals" '{"reason":"checked"}')
[[ "$(body_of "$res" | json_field "['status']")" == APPROVED ]] || { echo "FAIL: approval: $(body_of "$res")"; exit 1; }
quote=$(curl -s -H "Authorization: Bearer $TF_CHECKER" -H 'Content-Type: application/json' -H 'X-Channel: PORTAL' \
  "$FEES/v1/quotes" -d "{\"serviceCode\":\"$fee_service\",\"region\":\"DXB\",\"customerCategory\":\"CITIZEN\",\"quantities\":{\"YEAR\":\"2\"}}")
[[ "$(printf '%s' "$quote" | json_field "['total']")" == 20000 ]] || { echo "FAIL: quote $quote"; exit 1; }
echo "   fee BASE_$fee_service approved by the checker; quote total 20000 (2 years x 10000)"

S_MAKER=$(token dev.maker "$DEV_MAKER_PASSWORD") # fresh: earlier tokens may have expired
S_CHECKER=$(token dev.checker "$DEV_CHECKER_PASSWORD")
SMK=$(date +%s)
post_to() { # base token path body [channel] -> prints "<body>\n<status>"
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $2" -H 'Content-Type: application/json' \
    -H "X-Channel: ${5:-BACKOFFICE}" -H "Idempotency-Key: $(new_key)" "$1$3" -d "$4"
}
get_from() { curl -s -H "Authorization: Bearer $2" "$1$3"; } # base token path
db_sql() { "${DC[@]}" exec -T "$1" psql -qtA -U "$3" -d "$2" -c "$4"; } # container database user query
pay_sql() { db_sql payment-postgres payment "$PAY_DB_USER" "$1"; }
expect() { # result status [code]
  [[ "$(status_of "$1")" == "$2" ]] || { echo "FAIL: expected $2, got $(status_of "$1"): $(body_of "$1")"; exit 1; }
  [[ -z "${3:-}" || "$(body_of "$1" | json_field "['code']")" == "$3" ]] || { echo "FAIL: expected $3: $(body_of "$1")"; exit 1; }
}
wait_until() { # seconds command... : retries the command once a second until it succeeds
  local n=$1; shift
  for _ in $(seq 1 "$n"); do "$@" && return 0; sleep 1; done
  return 1
}
sign_callback() { # payment-id txn status amount currency paid-at -> ICD-03 HMAC-SHA256 hex
  "$PY" -c "import hmac,hashlib,sys; print(hmac.new(sys.argv[1].encode(), '|'.join(sys.argv[2:]).encode(), hashlib.sha256).hexdigest())" \
    "$DEV_PAYMENT_CALLBACK_KEY" "$1" "$2" "$3" "$4" "$5" "$6"
}
callback() { # payment-id txn amount -> result of a signed SUCCESS callback, relayed by dev.maker (PAYMENT_GATEWAY_ADAPTER)
  local at="2026-10-07T08:00:00Z" sig
  sig=$(sign_callback "$1" "$2" SUCCESS "$3" XTS "$at")
  post_to "$PAY" "$S_MAKER" "/v1/payments/$1/callbacks" \
    "{\"gatewayTxnId\":\"$2\",\"merchantRef\":\"$1\",\"status\":\"SUCCESS\",\"amountMinor\":$3,\"currency\":\"XTS\",\"paidAt\":\"$at\",\"signature\":\"$sig\"}" PARTNER
}
today=$(date -u +%F)
PAY=http://localhost:8094

echo "29. Business rules (CC-050..CC-052): maker submits a decision table, tests it, cannot approve it; the checker approves"
RULES=http://localhost:8087
rule_set="SMK_RULE_$SMK"
table='{"hitPolicy":"FIRST","inputs":[{"name":"age","type":"NUMBER"}],"outputs":[{"name":"eligible","type":"BOOLEAN"}],"rules":[{"when":{"age":{"op":"GE","values":[18]}},"then":{"eligible":true},"description":"adult"}],"defaults":{"eligible":false}}'
res=$(post_to "$RULES" "$S_MAKER" /v1/rule-versions "{\"ruleSetCode\":\"$rule_set\",\"nameNl\":\"العمر\",\"nameEn\":\"Minimum age\",\"table\":$table,\"effectiveFrom\":\"$today\",\"reason\":\"smoke test rule\"}")
expect "$res" 201
rule_id=$(body_of "$res" | json_field "['id']")
expect "$(post_to "$RULES" "$S_MAKER" "/v1/rule-versions/$rule_id/approvals" '{}')" 403
res=$(post_to "$RULES" "$S_MAKER" "/v1/rule-versions/$rule_id/test-runs" '{"cases":[{"inputs":{"age":18},"expected":{"eligible":true}},{"inputs":{"age":17},"expected":{"eligible":false}}]}')
expect "$res" 200
[[ "$(body_of "$res" | json_field "['passed']")" == 2 ]] || { echo "FAIL: test run $(body_of "$res")"; exit 1; }
res=$(post_to "$RULES" "$S_CHECKER" "/v1/rule-versions/$rule_id/approvals" '{"reason":"checked"}')
[[ "$(body_of "$res" | json_field "['status']")" == APPROVED ]] || { echo "FAIL: approval $(body_of "$res")"; exit 1; }
echo "   $rule_set v1: test run 2/2 passed; maker refused (403); checker approved"

echo "30. Workflow (CC-060, CC-061, CC-063): a case enters the officer queue, is claimed, decided by its assignee only, completed"
WF=http://localhost:8088
wf="WF_SMK$SMK"
res=$(post_to "$WF" "$S_MAKER" /v1/workflow-definitions "{\"code\":\"$wf\",\"serviceCode\":\"SMK_WF\",\"nameNl\":\"مراجعة\",\"nameEn\":\"Smoke review\",\"firstStep\":\"REVIEW\",\"steps\":[{\"code\":\"REVIEW\",\"kind\":\"REVIEW\",\"nameNl\":\"مراجعة\",\"nameEn\":\"Review\",\"role\":\"CASE_OFFICER\",\"assignment\":\"MANUAL\",\"sla\":\"PT24H\",\"escalationRole\":\"CASE_SUPERVISOR\",\"outcomes\":{\"APPROVED\":\"END\",\"REJECTED\":\"END\"}}],\"reason\":\"smoke test workflow\"}")
expect "$res" 201
res=$(post_to "$WF" "$S_MAKER" /v1/work-items "{\"workflowCode\":\"$wf\",\"reference\":\"SMK-$SMK\",\"serviceCode\":\"SMK_WF\",\"region\":\"DXB\"}")
expect "$res" 201
item=$(body_of "$res" | json_field "['id']")
[[ "$(body_of "$res" | json_field "['queueRole']")" == CASE_OFFICER ]] || { echo "FAIL: wrong queue $(body_of "$res")"; exit 1; }
expect "$(post_to "$WF" "$S_CHECKER" "/v1/work-items/$item/claims" '{}')" 200
expect "$(post_to "$WF" "$S_MAKER" "/v1/work-items/$item/decisions" '{"outcome":"APPROVED"}')" 403 NOT_ASSIGNEE
res=$(post_to "$WF" "$S_CHECKER" "/v1/work-items/$item/decisions" '{"outcome":"APPROVED","reason":"documents complete"}')
[[ "$(body_of "$res" | json_field "['status']")" == COMPLETED ]] || { echo "FAIL: decision $(body_of "$res")"; exit 1; }
steps=$(get_from "$WF" "$S_CHECKER" "/v1/work-items/$item/history" | json_field "['items'].__len__()")
(( steps >= 3 )) || { echo "FAIL: expected >= 3 history entries, got $steps"; exit 1; }
echo "   case $item: claimed by the officer, the supervisor refused (not assignee), completed APPROVED ($steps history entries)"

echo "31. Notifications (CC-070, CC-073, CC-074): a bilingual template is sent; the SMS reaches the gateway mock, the inbox is delivered"
NOTIF=http://localhost:8089
tpl="SMK_TPL_$SMK"
recipient="smk-r-$SMK"
res=$(post_to "$NOTIF" "$S_MAKER" /v1/notification-templates "{\"code\":\"$tpl\",\"category\":\"SMOKE\",\"mandatory\":false,\"contents\":{\"SMS\":{\"bodyNl\":\"مرحبا {{name}}\",\"bodyEn\":\"Hello {{name}}\"},\"INBOX\":{\"subjectNl\":\"تحية\",\"subjectEn\":\"Greeting\",\"bodyNl\":\"مرحبا {{name}}\",\"bodyEn\":\"Hello {{name}}\"}},\"reason\":\"smoke test template\"}")
expect "$res" 201
expect "$(post_to "$NOTIF" "$S_CHECKER" "/v1/recipients/$recipient" '{"language":"EN","phone":"+971501234567"}')" 200
res=$(post_to "$NOTIF" "$S_MAKER" /v1/notifications "{\"templateCode\":\"$tpl\",\"recipientId\":\"$recipient\",\"data\":{\"name\":\"Smoke\"},\"reference\":\"SMK-$SMK\"}")
expect "$res" 201
sms=$(body_of "$res" | "$PY" -c "import json,sys; print(next(i['id'] for i in json.load(sys.stdin)['items'] if i['channel']=='SMS'))")
inbox=$(body_of "$res" | "$PY" -c "import json,sys; print(next(i['status'] for i in json.load(sys.stdin)['items'] if i['channel']=='INBOX'))")
[[ "$inbox" == DELIVERED ]] || { echo "FAIL: inbox message $inbox"; exit 1; }
sms_sent() { [[ "$(get_from "$NOTIF" "$S_CHECKER" "/v1/notifications/$sms" | json_field "['status']")" == SENT ]]; }
wait_until 60 sms_sent || { echo "FAIL: the SMS was not sent to the gateway mock"; exit 1; }
echo "   template $tpl: SMS $sms SENT through the messaging-gateway mock; inbox DELIVERED"

echo "32. Documents and certificates (CC-080..CC-083): upload scanned by ClamAV into MinIO, download intact; certificate verified publicly"
DOC=http://localhost:8091
res=$(post_to "$DOC" "$S_MAKER" /v1/documents "{\"type\":\"SUPPORTING_DOCUMENT\",\"ownerType\":\"VEHICLE\",\"ownerId\":\"SMK-$SMK\",\"title\":\"Smoke document\"}")
expect "$res" 201
doc=$(body_of "$res" | json_field "['id']")
pdf=$(mktemp); printf '%%PDF-1.7\n1 0 obj << >> endobj\n%%%%EOF smoke %s\n' "$SMK" > "$pdf"
res=$(curl -s -w '\n%{http_code}' -H "Authorization: Bearer $S_MAKER" -H 'X-Channel: BACKOFFICE' \
  -H 'Content-Type: application/octet-stream' --data-binary "@$pdf" "$DOC/v1/documents/$doc/content")
expect "$res" 200
[[ "$(body_of "$res" | json_field "['status']")" == AVAILABLE ]] || { echo "FAIL: upload $(body_of "$res")"; exit 1; }
stored_sha=$(body_of "$res" | json_field "['sha256']")
local_sha=$("$PY" -c "import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$pdf")
[[ "$stored_sha" == "$local_sha" ]] || { echo "FAIL: stored digest differs"; exit 1; }
down_sha=$(curl -s -H "Authorization: Bearer $S_MAKER" "$DOC/v1/documents/$doc/content" | "$PY" -c "import hashlib,sys; print(hashlib.sha256(sys.stdin.buffer.read()).hexdigest())")
[[ "$down_sha" == "$local_sha" ]] || { echo "FAIL: downloaded content differs"; exit 1; }
rm -f "$pdf"
exe_doc=$(body_of "$(post_to "$DOC" "$S_MAKER" /v1/documents "{\"type\":\"SUPPORTING_DOCUMENT\",\"ownerType\":\"VEHICLE\",\"ownerId\":\"SMK-$SMK\"}")" | json_field "['id']")
res=$(printf 'MZ\x90\x00 not a pdf' | curl -s -w '\n%{http_code}' -H "Authorization: Bearer $S_MAKER" -H 'X-Channel: BACKOFFICE' \
  -H 'Content-Type: application/octet-stream' --data-binary @- "$DOC/v1/documents/$exe_doc/content")
expect "$res" 422 FILE_TYPE_NOT_ALLOWED
cert_tpl="SMK_CERT_$SMK"
expect "$(post_to "$DOC" "$S_CHECKER" /v1/certificate-templates "{\"code\":\"$cert_tpl\",\"titleNl\":\"شهادة\",\"titleEn\":\"Smoke certificate\",\"bodyNl\":\"نشهد أن {{holderNameNl}}\",\"bodyEn\":\"We certify that {{holderNameEn}}\",\"validityDays\":30,\"reason\":\"smoke test template\"}")" 201
res=$(post_to "$DOC" "$S_MAKER" /v1/certificates "{\"templateCode\":\"$cert_tpl\",\"holderNameNl\":\"اختبار\",\"holderNameEn\":\"Smoke Tester\",\"subjectType\":\"VEHICLE\",\"subjectId\":\"SMK-$SMK\"}")
expect "$res" 201
cert=$(body_of "$res" | json_field "['number']")
cert_code=$(body_of "$res" | json_field "['verificationCode']")
validity=$(curl -s "$GW/v1/certificate-verifications/$cert?code=$cert_code" | json_field "['validity']")
[[ "$validity" == VALID ]] || { echo "FAIL: public verification through the gateway returned $validity"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' "$GW/v1/certificate-verifications/$cert?code=WRONGCODE2")
[[ "$status" == 404 ]] || { echo "FAIL: a wrong code returned $status"; exit 1; }
echo "   document $doc AVAILABLE (digest matches both ways), executable refused; certificate $cert VALID without a token, wrong code 404"

echo "33. Appointments (CC-090..CC-092): a calendar is set up; an agent books the last place for a customer; a second booking is refused"
APPT=http://localhost:8092
centre="SMK$SMK"
hours=$("$PY" -c "import json; print(json.dumps({d: [{'opens': '08:00', 'closes': '12:00'}] for d in ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY']}))")
expect "$(post_to "$APPT" "$S_MAKER" /v1/appointment-centres "{\"code\":\"$centre\",\"region\":\"DXB\",\"zone\":\"Asia/Dubai\",\"weeklyHours\":$hours,\"active\":true}")" 200
expect "$(post_to "$APPT" "$S_MAKER" /v1/appointment-resources "{\"code\":\"${centre}_R\",\"centreCode\":\"$centre\",\"type\":\"EXAMINER\",\"name\":\"Smoke examiner\",\"services\":[\"SMK_TEST\"],\"capacity\":1,\"active\":true}")" 200
expect "$(post_to "$APPT" "$S_MAKER" /v1/appointment-service-settings '{"serviceCode":"SMK_TEST","slotMinutes":30,"horizonDays":30,"minLeadMinutes":60,"maxChanges":2,"noShowLimit":2,"noShowWindowDays":90,"penaltyDays":30}')" 200
tomorrow=$(date -u -d tomorrow +%F 2>/dev/null || "$PY" -c "import datetime; print(datetime.date.today() + datetime.timedelta(days=1))")
slot=$(get_from "$APPT" "$S_CHECKER" "/v1/slots?centreCode=$centre&serviceCode=SMK_TEST&from=$tomorrow&to=$tomorrow" | json_field "['items'][0]['start']")
slot_utc=$("$PY" -c "import datetime,sys; print(datetime.datetime.fromisoformat(sys.argv[1].replace('Z','+00:00')).astimezone(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$slot")
res=$(post_to "$APPT" "$S_CHECKER" /v1/bookings "{\"serviceCode\":\"SMK_TEST\",\"centreCode\":\"$centre\",\"start\":\"$slot_utc\",\"customerId\":\"smk-c1-$SMK\"}")
expect "$res" 201
booking=$(body_of "$res" | json_field "['id']")
expect "$(post_to "$APPT" "$S_CHECKER" /v1/bookings "{\"serviceCode\":\"SMK_TEST\",\"centreCode\":\"$centre\",\"start\":\"$slot_utc\",\"customerId\":\"smk-c2-$SMK\"}")" 409 SLOT_NOT_AVAILABLE
res=$(post_to "$APPT" "$S_CHECKER" "/v1/bookings/$booking/cancellations" '{"reason":"smoke test cancellation"}')
[[ "$(body_of "$res" | json_field "['status']")" == CANCELLED ]] || { echo "FAIL: cancellation $(body_of "$res")"; exit 1; }
echo "   centre $centre: booking $booking at $slot_utc, second customer refused (capacity 1), cancelled"

echo "34. Central audit trail (CC-120..CC-122): step 29's rule version reaches the hash chain through Kafka; the chain is intact"
AUD=http://localhost:8093
rule_timeline() { get_from "$AUD" "$S_CHECKER" "/v1/timelines/RuleVersion/$rule_id" | "$PY" -c "import json,sys; a=[i['action'] for i in json.load(sys.stdin)['items']]; sys.exit(0 if 'RULE_VERSION_APPROVED' in a and 'RULE_VERSION_SUBMITTED' in a else 1)"; }
wait_until 90 rule_timeline || { echo "FAIL: rule version $rule_id not in the central audit trail"; exit 1; }
hash=$(get_from "$AUD" "$S_CHECKER" "/v1/timelines/RuleVersion/$rule_id" | json_field "['items'][0]['hash']")
[[ ${#hash} == 64 ]] || { echo "FAIL: audit record without a chain hash"; exit 1; }
intact=$(get_from "$AUD" "$S_CHECKER" "/v1/audit-integrity?limit=100000" | json_field "['intact']")
[[ "$intact" == True ]] || { echo "FAIL: audit chain broken"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $S_CHECKER" "$AUD/v1/audit-records?from=2026-01-01T00:00:00Z")
[[ "$status" == 422 ]] || { echo "FAIL: a search without a filter returned $status"; exit 1; }
status=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $S_MAKER" "$AUD/v1/audit-records?actorId=x")
[[ "$status" == 403 ]] || { echo "FAIL: a non-auditor searched the audit trail ($status)"; exit 1; }
echo "   RuleVersion $rule_id: submitted and approved in the chain; chain intact; filterless search 422; non-auditor 403"

echo "35. Payments (FN-001..FN-004, FN-011, FN-022): counter payment with a numbered receipt, same-day cancellation; signed gateway callback"
pay_general() { # item-ref amount -> payable id
  post_to "$PAY" "$S_MAKER" /v1/payables "{\"itemType\":\"GENERAL\",\"itemRef\":\"$1\",\"region\":\"DXB\",\"currency\":\"XTS\",\"lines\":[{\"financialItem\":\"SMOKE_REVENUE\",\"beneficiary\":\"AUTHORITY\",\"amountMinor\":$2}]}" \
    | body_of_pipe | json_field "['id']"
}
body_of_pipe() { sed '$d'; }
basket_of() { # payable ids as a JSON list -> basket id
  post_to "$PAY" "$S_MAKER" /v1/baskets "{\"payableIds\":$1}" PORTAL | body_of_pipe | json_field "['id']"
}
payable=$(pay_general "SMK-PAY-$SMK" 1500)
res=$(post_to "$PAY" "$S_MAKER" "/v1/baskets/$(basket_of "[\"$payable\"]")/payments" "{\"method\":\"CASH\",\"counterReference\":\"TILL-SMK-$SMK\"}")
expect "$res" 201
receipt=$(body_of "$res" | json_field "['receipt']['number']")
[[ "$receipt" =~ ^R-DXB-BACKOFFICE-[0-9]{4}-[0-9]{8}$ ]] || { echo "FAIL: receipt number $receipt"; exit 1; }
expect "$(post_to "$PAY" "$S_MAKER" "/v1/baskets/$(basket_of "[\"$(pay_general "SMK-PAY2-$SMK" 700)\"]")/payments" "{\"method\":\"CASH\",\"counterReference\":\"TILL-SMK-$SMK\"}")" 409 TRANSACTION_REFERENCE_USED
expect "$(post_to "$PAY" "$S_MAKER" "/v1/receipts/$receipt/cancellations" '{"reason":"smoke test"}')" 403
res=$(post_to "$PAY" "$S_CHECKER" "/v1/receipts/$receipt/cancellations" '{"reason":"smoke test cancellation"}')
[[ "$(body_of "$res" | json_field "['status']")" == CANCELLED ]] || { echo "FAIL: cancellation $(body_of "$res")"; exit 1; }
[[ "$(get_from "$PAY" "$S_MAKER" "/v1/payables/$payable" | json_field "['status']")" == OPEN ]] || { echo "FAIL: payable not reopened"; exit 1; }
gpayable=$(pay_general "SMK-PAYG-$SMK" 2500)
res=$(post_to "$PAY" "$S_MAKER" "/v1/baskets/$(basket_of "[\"$gpayable\"]")/payments" '{"method":"GATEWAY_CARD","returnUrl":"http://localhost/return"}' PORTAL)
expect "$res" 201
gpayment=$(body_of "$res" | json_field "['payment']['id']")
first=$(body_of "$(callback "$gpayment" "gw-smk-$SMK" 2500)" | json_field "['receiptNumber']")
[[ -n "$first" && "$first" != None ]] || { echo "FAIL: the gateway callback gave no receipt"; exit 1; }
expect "$(post_to "$PAY" "$S_MAKER" "/v1/payments/$gpayment/callbacks" "{\"gatewayTxnId\":\"x\",\"merchantRef\":\"$gpayment\",\"status\":\"SUCCESS\",\"amountMinor\":2500,\"currency\":\"XTS\",\"signature\":\"$(printf '0%.0s' $(seq 1 64))\"}" PARTNER)" 401 SIGNATURE_INVALID
echo "   receipt $receipt cancelled by the supervisor (payable open again); gateway payment confirmed by a signed callback, receipt $first; forged signature 401"

echo "36. Vehicles (VL-010, VL-024, VL-032; UAT-3 rule): insurance stops registration before payment; a paid registration assigns a plate"
# The insurer mock has no "expired yesterday" policy and an expired VIN cannot be registered first, so the smoke shows
# the UAT-3 check and message on a first registration; the renewal itself is VehicleApiIT.uat3RenewalBlockedByInsurance.
VEH=http://localhost:8095
FEES=http://localhost:8086
quote_total=$(curl -s -H "Authorization: Bearer $S_MAKER" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' "$FEES/v1/quotes" \
  -d '{"serviceCode":"VEH_FIRST_REG","region":"DXB","customerCategory":"CITIZEN","subjectCategory":"PRIVATE"}' \
  | "$PY" -c "import json,sys; print(json.load(sys.stdin).get('total', 0))" 2>/dev/null || echo 0)
if [[ "$quote_total" == 0 ]]; then # first run on this stack: publish the first-registration fee
  res=$(post_to "$FEES" "$S_MAKER" /v1/fee-versions "{\"feeCode\":\"VEH_FIRST_REG_FEE\",\"serviceCode\":\"VEH_FIRST_REG\",\"nameNl\":\"رسم التسجيل\",\"nameEn\":\"First registration fee\",\"financialItem\":\"REV_VEHICLE_REG\",\"beneficiary\":\"AUTHORITY\",\"method\":{\"type\":\"FIXED\",\"amount\":40000},\"effectiveFrom\":\"$today\",\"reason\":\"smoke test vehicle fee\"}")
  expect "$res" 201
  expect "$(post_to "$FEES" "$S_CHECKER" "/v1/fee-versions/$(body_of "$res" | json_field "['id']")/approvals" '{"reason":"checked"}')" 200
fi
expect "$(post_to "$VEH" "$S_CHECKER" /v1/plate-series '{"code":"SMK_DXB_PRIVATE","region":"DXB","plateCategory":"PRIVATE","colourCode":"WHITE","design":"STANDARD","prefix":"S","firstNumber":1,"lastNumber":99999,"premiumPattern":"^(\\d)\\1+$","active":true}')" 200
new_vehicle() { # vin
  post_to "$VEH" "$S_MAKER" /v1/vehicles "{\"vin\":\"$1\",\"spec\":{\"engineNo\":\"ENG-$SMK\",\"make\":\"TOYOTA\",\"model\":\"COROLLA\",\"modelYear\":$(date -u +%Y),\"bodyType\":\"SEDAN\",\"colours\":[\"WHITE\"],\"fuelType\":\"PETROL\",\"steering\":\"LEFT\",\"seats\":5,\"emptyWeightKg\":1200,\"grossWeightKg\":1700,\"axles\":2,\"cylinders\":4,\"category\":\"PRIVATE\"},\"ownerFileNumber\":\"TF-SMK-$SMK\",\"region\":\"DXB\"}"
}
expired_vin=$(printf 'WDB%09d00000' "$((SMK % 1000000000))")
expect "$(new_vehicle "$expired_vin")" 201
res=$(post_to "$VEH" "$S_MAKER" "/v1/vehicles/$expired_vin/registrations" '{"customerCategory":"CITIZEN"}' PORTAL)
expect "$res" 409 INSURANCE_EXPIRED
[[ "$(body_of "$res" | json_field "['detail']")" == "Your vehicle insurance has expired. Renew it with your insurer, then try again." ]] \
  || { echo "FAIL: UAT-3 message $(body_of "$res")"; exit 1; }
[[ "$(pay_sql "select count(*) from payable where traffic_file_number = 'TF-SMK-$SMK'")" == 0 ]]   || { echo "FAIL: a payable was registered although insurance stopped the registration"; exit 1; }
down_vin=$(printf 'WDB%09d99999' "$((SMK % 1000000000))")
expect "$(new_vehicle "$down_vin")" 201
expect "$(post_to "$VEH" "$S_MAKER" "/v1/vehicles/$down_vin/registrations" '{"customerCategory":"CITIZEN"}' PORTAL)" 503 INSURANCE_UNAVAILABLE
vin=$(printf 'WDB%013d1' "$SMK")
expect "$(new_vehicle "$vin")" 201
res=$(post_to "$VEH" "$S_MAKER" "/v1/vehicles/$vin/registrations" '{"customerCategory":"CITIZEN"}' PORTAL)
expect "$res" 201
item_ref="VR-$(body_of "$res" | json_field "['id']" | tr '[:lower:]' '[:upper:]')"
reg_payable=$(pay_sql "select id from payable where item_ref = '$item_ref'")
[[ -n "$reg_payable" ]] || { echo "FAIL: no payable $item_ref at payment-service"; exit 1; }
expect "$(post_to "$PAY" "$S_MAKER" "/v1/baskets/$(basket_of "[\"$reg_payable\"]")/payments" "{\"method\":\"CASH\",\"counterReference\":\"TILL-VEH-$SMK\"}")" 201
registered() { [[ "$(get_from "$VEH" "$S_MAKER" "/v1/vehicles/$vin" | json_field "['status']")" == REGISTERED ]]; }
wait_until 60 registered || { echo "FAIL: vehicle $vin not registered after its payment"; exit 1; }
plate=$(get_from "$VEH" "$S_MAKER" "/v1/vehicles/$vin" | json_field "['plateNumber']")
[[ "$plate" == S\ * ]] || { echo "FAIL: plate $plate"; exit 1; }
echo "   UAT-3: $expired_vin stopped before payment with the exact message; $down_vin insurer down 503; $vin paid at the counter -> REGISTERED, plate $plate"

echo "37. Violations (VF-030..VF-032, VF-070; UAT-1, UAT-4): 20 + 6 points suspend the licence with a hold; three fines paid once"
VIO=http://localhost:8096
catalogue() { # code fine points
  expect "$(post_to "$VIO" "$S_MAKER" /v1/violation-catalogue "{\"code\":\"$1\",\"legalArticle\":\"Art. 10\",\"descriptionNl\":\"مخالفة\",\"descriptionEn\":\"Smoke violation $1\",\"fineMinor\":$2,\"blackPoints\":$3,\"impoundDays\":0,\"suspensionDays\":0,\"courtReferral\":false,\"appliesTo\":\"DRIVER\",\"financialItem\":\"FINE_REVENUE\",\"beneficiary\":\"AUTHORITY\",\"effectiveFrom\":\"$today\",\"reason\":\"smoke test catalogue\"}")" 201
}
record() { # code owner-file driver-file -> violation number
  local occurred driver
  occurred=$(date -u -d '-1 hour' +%FT%TZ 2>/dev/null || "$PY" -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
  driver=${3:+,\"driverFileNumber\":\"$3\"}
  post_to "$VIO" "$S_MAKER" /v1/violations "{\"catalogueCode\":\"$1\",\"occurredAt\":\"$occurred\",\"region\":\"DXB\",\"location\":\"Smoke road km 1\",\"plate\":\"S 1\",\"ownerFileNumber\":\"$2\"$driver,\"source\":\"OFFICER\"}" FIELD \
    | body_of_pipe | json_field "['number']"
}
catalogue "SMK20_$SMK" 30000 20
catalogue "SMK6_$SMK" 60000 6
catalogue "SMKP_$SMK" 10000 0
driver="TF-SMKD-$SMK" # throw-away file per run; it keeps its suspension and hold (harmless)
record "SMK20_$SMK" "TF-SMKO-$SMK" "$driver" >/dev/null
[[ "$(get_from "$VIO" "$S_MAKER" "/v1/black-point-accounts/$driver" | json_field "['activePoints']")" == 20 ]] || { echo "FAIL: 20 points expected"; exit 1; }
record "SMK6_$SMK" "TF-SMKO-$SMK" "$driver" >/dev/null
account=$(get_from "$VIO" "$S_MAKER" "/v1/black-point-accounts/$driver")
until_expected=$("$PY" -c "import datetime,calendar; d=datetime.datetime.now(datetime.timezone.utc).date(); m=d.month+2; y=d.year+m//12; m=m%12+1; e=datetime.date(y,m,min(d.day,calendar.monthrange(y,m)[1])); print(e-datetime.timedelta(days=1))")
printf '%s' "$account" | "$PY" -c "import json,sys; s=json.load(sys.stdin)['activeSuspension']; assert s and s['pointsAtOrder']==26 and s['ordinal']==1 and s['until']==sys.argv[1], s" "$until_expected" \
  || { echo "FAIL: UAT-1 suspension $account"; exit 1; }
holds=$(get_from http://localhost:8084 "$S_CHECKER" "/v1/holds?subjectType=TRAFFIC_FILE&subjectId=$driver" \
  | "$PY" -c "import json,sys; print(','.join(h['holdType'] + '/' + h['status'] for h in json.load(sys.stdin)['items']))")
[[ "$holds" == *LICENCE_SUSPENSION/ACTIVE* ]] || { echo "FAIL: no active suspension hold on $driver ($holds)"; exit 1; }
owner="TF-SMKO3-$SMK"
v1=$(record "SMKP_$SMK" "$owner"); v2=$(record "SMKP_$SMK" "$owner"); v3=$(record "SMKP_$SMK" "$owner")
ids=$(pay_sql "select json_agg(id) from payable where item_ref in ('$v1','$v2','$v3')")
[[ "$(printf '%s' "$ids" | json_field ".__len__()")" == 3 ]] || { echo "FAIL: payables of the three fines: $ids"; exit 1; }
res=$(post_to "$PAY" "$S_MAKER" "/v1/baskets/$(basket_of "$ids")/payments" '{"method":"GATEWAY_CARD","returnUrl":"http://localhost/return"}' PORTAL)
expect "$res" 201
vpayment=$(body_of "$res" | json_field "['payment']['id']")
r1=$(body_of "$(callback "$vpayment" "gw-uat4-$SMK" 30000)")
r2=$(body_of "$(callback "$vpayment" "gw-uat4-$SMK" 30000)")
[[ "$(printf '%s' "$r1" | json_field "['duplicate']")" == False && "$(printf '%s' "$r2" | json_field "['duplicate']")" == True ]] \
  || { echo "FAIL: UAT-4 callbacks $r1 / $r2"; exit 1; }
[[ "$(printf '%s' "$r1" | json_field "['receiptNumber']")" == "$(printf '%s' "$r2" | json_field "['receiptNumber']")" ]] || { echo "FAIL: two receipts"; exit 1; }
[[ "$(pay_sql "select count(*) from receipt where payment_id = '$vpayment'")" == 1 ]] || { echo "FAIL: UAT-4 receipts"; exit 1; }
[[ "$(pay_sql "select string_agg(outcome, ',' order by id) from payment_callback_log where payment_id = '$vpayment'")" == ACCEPTED,DUPLICATE ]] \
  || { echo "FAIL: UAT-4 callback log"; exit 1; }
all_paid() { for n in "$v1" "$v2" "$v3"; do [[ "$(get_from "$VIO" "$S_MAKER" "/v1/violations/$n" | json_field "['status']")" == PAID ]] || return 1; done; }
wait_until 60 all_paid || { echo "FAIL: UAT-4 violations not all PAID"; exit 1; }
echo "   UAT-1 (points at recording, Q-072): $driver 26 points -> suspended until $until_expected, LICENCE_SUSPENSION hold active"
echo "   UAT-4: $v1 $v2 $v3 in one basket; two identical callbacks -> one receipt, second DUPLICATE; all three PAID"

echo "SMOKE OK"
