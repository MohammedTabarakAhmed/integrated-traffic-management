#!/usr/bin/env bash
# Demo data for the interview showcase (the project guide §10, demo step D1.1), through the public APIs only.
# Gives the demo customer 784199012345671 (identity-register mock, signs in through the mock national digital ID) a
# traffic file, three violations (10 black points, so the control room can take them to 24 live), one open
# transaction and, through the violation events, inbox messages. Safe to run again: it adds nothing twice.
# Run after tools/dev/up.sh: ITMS_LOCAL_PROFILE=lite bash tools/dev/demo-seed.sh [--reset-otp]
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; source infra/local/.env; set +a
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python

NATIONAL_ID=784199012345671
TOKEN_URL=http://localhost:8180/realms/itms/protocol/openid-connect/token
TF=http://localhost:8085; TXN=http://localhost:8083; VIO=http://localhost:8096

token() {
  curl -sf "$TOKEN_URL" -d grant_type=password -d client_id=itms-dev-cli -d scope=openid \
    --data-urlencode "username=$1" --data-urlencode "password=$2" | "$PY" -c "import json,sys; print(json.load(sys.stdin)['access_token'])"
}
json_field() { "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
key() { printf 'demo-%s-%s' "$(date +%s%N)" "$RANDOM"; }
post() { # token url body [channel] -> "<body>\n<status>"; the body goes on stdin so Arabic survives on Windows
  curl -s -w '\n%{http_code}' -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -H "X-Channel: ${4:-BACKOFFICE}" -H "Idempotency-Key: $(key)" "$2" --data-binary @- <<<"$3"
}
status_of() { printf '%s' "$1" | tail -n 1; }
body_of() { printf '%s' "$1" | sed '$d'; }
need() { # result allowed-status... ; stops with the answer otherwise
  local s; s=$(status_of "$1"); shift
  for ok in "$@"; do [[ "$s" == "$ok" ]] && return 0; done
  echo "FAIL: HTTP $s: $(body_of "$1")"; exit 1
}

if [[ "${1:-}" == "--reset-otp" ]]; then # the presenter enrols their own authenticator at the next step-up (SEC-002)
  KC=(env MSYS_NO_PATHCONV=1 docker exec itms-keycloak-1 /opt/keycloak/bin/kcadm.sh)
  "${KC[@]}" config credentials --server http://localhost:8080 --realm master \
    --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null
  uid=$("${KC[@]}" get users -r national-id-mock -q "username=$NATIONAL_ID" -q exact=true --fields id --format csv --noquotes | tr -d '\r')
  for cid in $("${KC[@]}" get "users/$uid/credentials" -r national-id-mock --fields id,type --format csv --noquotes \
      | tr -d '\r' | awk -F, '$2 == "otp" {print $1}'); do
    "${KC[@]}" delete "users/$uid/credentials/$cid" -r national-id-mock
  done
  echo "0. One-time password authenticator of $NATIONAL_ID removed: the next step-up enrols a new one"
fi

MAKER=$(token dev.maker "$DEV_MAKER_PASSWORD")
CHECKER=$(token dev.checker "$DEV_CHECKER_PASSWORD")

echo "1. Traffic file of the demo customer $NATIONAL_ID"
res=$(post "$CHECKER" "$TF/v1/traffic-files/individuals" "{\"document\":{\"type\":\"NATIONAL_ID\",\"nationalIdNo\":\"$NATIONAL_ID\"},\"category\":\"INDIVIDUAL\",\"type\":\"CITIZEN\",\"regionOpened\":\"DXB\",\"profile\":{\"mobile\":\"+971500000001\",\"languages\":[\"AR\",\"EN\"]}}")
need "$res" 201 409
FILE=$(curl -sf -H "Authorization: Bearer $CHECKER" -H 'Content-Type: application/json' "$TF/v1/traffic-files/searches" \
  -d "{\"document\":{\"type\":\"NATIONAL_ID\",\"nationalIdNo\":\"$NATIONAL_ID\"}}" | json_field "['items'][0]['fileNumber']")
echo "   file $FILE"

echo "2. Violation catalogue entries for the demo (bilingual)"
today=$(date -u +%F) # a catalogue change cannot start in the past (VF-002), so the violations below are minutes old
catalogue() { # code fine-minor points arabic english
  local versions; versions=$(curl -sf -H "Authorization: Bearer $MAKER" "$VIO/v1/violation-catalogue/$1" | json_field "['items'].__len__()")
  [[ "$versions" -gt 0 ]] && return 0
  need "$(post "$MAKER" "$VIO/v1/violation-catalogue" "{\"code\":\"$1\",\"legalArticle\":\"Art. 47\",\"descriptionNl\":\"$4\",\"descriptionEn\":\"$5\",\"fineMinor\":$2,\"blackPoints\":$3,\"impoundDays\":0,\"suspensionDays\":0,\"courtReferral\":false,\"appliesTo\":\"DRIVER\",\"financialItem\":\"FINE_REVENUE\",\"beneficiary\":\"AUTHORITY\",\"effectiveFrom\":\"$today\",\"reason\":\"demo catalogue\"}")" 201
}
catalogue DEMO_SPEED 60000 4 "تجاوز السرعة المقررة بأكثر من 20 كم/س" "Speeding more than 20 km/h over the limit"
catalogue DEMO_PHONE 80000 6 "استخدام الهاتف أثناء القيادة" "Using a phone while driving"
catalogue DEMO_PARK 20000 0 "الوقوف في مكان غير مخصص" "Parking in a prohibited place"
catalogue DEMO_REDLIGHT 300000 12 "تجاوز الإشارة الضوئية الحمراء" "Running a red light"
echo "   DEMO_SPEED (4 points), DEMO_PHONE (6), DEMO_PARK (0), DEMO_REDLIGHT (12, for the control room)"

echo "3. Three open violations of the demo customer (re-armed after a demo payment)"
existing=$(curl -sf -H "Authorization: Bearer $MAKER" "$VIO/v1/violations?fileNumber=$FILE&status=OPEN&limit=500" | json_field "['items'].__len__()")
if [[ "$existing" -lt 3 ]]; then
  record() { # code minutes-ago location plate
    local at; at=$("$PY" -c "import datetime,sys; print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=int(sys.argv[1]))).strftime('%Y-%m-%dT%H:%M:%SZ'))" "$2")
    need "$(post "$MAKER" "$VIO/v1/violations" "{\"catalogueCode\":\"$1\",\"occurredAt\":\"$at\",\"region\":\"DXB\",\"location\":\"$3\",\"plate\":\"$4\",\"ownerFileNumber\":\"$FILE\",\"driverFileNumber\":\"$FILE\",\"source\":\"OFFICER\"}" FIELD)" 201
  }
  ever=$(curl -sf -H "Authorization: Bearer $MAKER" "$VIO/v1/violations?fileNumber=$FILE&limit=500" | json_field "['items'].__len__()")
  if [[ "$ever" -eq 0 ]]; then
    record DEMO_SPEED 25 "Sheikh Zayed Road, km 24" "A 12345"
    record DEMO_PHONE 15 "Al Khail Road, exit 41" "A 12345"
    record DEMO_PARK 5 "Dubai Mall, P2" "A 12345"
    echo "   recorded 3 (10 points, added when the objection window lapses: locally one minute)"
  else # after a demo payment: new fines without points, so the control room still starts below 24
    record DEMO_PARK 20 "Dubai Mall, P2" "A 12345"
    record DEMO_PARK 10 "City Walk, B1" "A 12345"
    record DEMO_PARK 3 "JBR, The Walk" "A 12345"
    echo "   re-armed with 3 parking fines (no points)"
  fi
else
  echo "   already has $existing open"
fi

echo "4. One open transaction (licence renewal) for the dashboard"
SVC=DEMO_RENEWAL
if ! curl -sf -o /dev/null -H "Authorization: Bearer $MAKER" "$TXN/v1/services/$SVC"; then
  spec="{\"code\":\"$SVC\",\"name\":{\"nl\":\"تجديد رخصة القيادة\",\"en\":\"Driving licence renewal\"},\"module\":\"LICENSING\",\"customerTypes\":[\"INDIVIDUAL\"],\"channels\":[\"PORTAL\",\"APP\",\"BACKOFFICE\"],\"requiredDocuments\":[],\"sla\":\"P3D\",\"regions\":[\"*\"],\"anyRegion\":true,\"allowsConcurrentOpen\":false}"
  res=$(post "$MAKER" "$TXN/v1/catalogue-changes" "{\"changeType\":\"CREATE_SERVICE\",\"serviceCode\":\"$SVC\",\"service\":$spec,\"reason\":\"demo service\"}")
  need "$res" 201
  need "$(post "$CHECKER" "$TXN/v1/catalogue-changes/$(body_of "$res" | json_field "['id']")/approvals" "")" 200
fi
res=$(post "$CHECKER" "$TXN/v1/transactions" "{\"serviceCode\":\"$SVC\",\"subject\":{\"type\":\"TRAFFIC_FILE\",\"id\":\"$FILE\"},\"applicantFileNumber\":\"$FILE\",\"homeRegion\":\"DXB\"}" PORTAL)
need "$res" 201 409
echo "   $SVC for $FILE ($( [[ "$(status_of "$res")" == 201 ]] && body_of "$res" | json_field "['reference']" || echo 'already open'))"

echo "DEMO SEED OK — sign in as $NATIONAL_ID (password DEV_CUSTOMER_PASSWORD in infra/local/.env)"
