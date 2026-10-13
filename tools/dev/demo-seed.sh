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

echo "4. Transactions for the dashboard: an open renewal, a submitted address update, a cancelled replacement"
service() { # code module arabic english -> created once, maker then checker (CC-114)
  curl -sf -o /dev/null -H "Authorization: Bearer $MAKER" "$TXN/v1/services/$1" && return 0
  local spec="{\"code\":\"$1\",\"name\":{\"nl\":\"$3\",\"en\":\"$4\"},\"module\":\"$2\",\"customerTypes\":[\"INDIVIDUAL\"],\"channels\":[\"PORTAL\",\"APP\",\"BACKOFFICE\"],\"requiredDocuments\":[],\"sla\":\"P3D\",\"regions\":[\"*\"],\"anyRegion\":true,\"allowsConcurrentOpen\":false}"
  local res; res=$(post "$MAKER" "$TXN/v1/catalogue-changes" "{\"changeType\":\"CREATE_SERVICE\",\"serviceCode\":\"$1\",\"service\":$spec,\"reason\":\"demo service\"}")
  need "$res" 201
  need "$(post "$CHECKER" "$TXN/v1/catalogue-changes/$(body_of "$res" | json_field "['id']")/approvals" "")" 200
}
open_txn() { # service -> "<status> <reference or empty>"
  local res; res=$(post "$CHECKER" "$TXN/v1/transactions" "{\"serviceCode\":\"$1\",\"subject\":{\"type\":\"TRAFFIC_FILE\",\"id\":\"$FILE\"},\"applicantFileNumber\":\"$FILE\",\"homeRegion\":\"DXB\"}" PORTAL)
  need "$res" 201 409
  printf '%s %s' "$(status_of "$res")" "$( [[ "$(status_of "$res")" == 201 ]] && body_of "$res" | json_field "['reference']")"
}
service DEMO_RENEWAL LICENSING "تجديد رخصة القيادة" "Driving licence renewal"
service DEMO_ADDRESS TRAFFIC_FILE "تحديث العنوان" "Address update"
service DEMO_CARD LICENSING "بدل فاقد لبطاقة الرخصة" "Licence card replacement"
read -r s ref <<<"$(open_txn DEMO_RENEWAL)"; echo "   DEMO_RENEWAL ${ref:-already open}"
read -r s ref <<<"$(open_txn DEMO_ADDRESS)"
if [[ "$s" == 201 ]]; then # no fee: payment is skipped (Q-004); a hold on the file answers 422 ELIGIBILITY_BLOCKED (UAT-1)
  res=$(post "$CHECKER" "$TXN/v1/transactions/$ref/submissions" "" PORTAL); need "$res" 200 201 422
  echo "   DEMO_ADDRESS $ref submitted: HTTP $(status_of "$res")"
fi
read -r s ref <<<"$(open_txn DEMO_CARD)"
if [[ "$s" == 201 ]]; then
  need "$(post "$CHECKER" "$TXN/v1/transactions/$ref/cancellations" '{"reason":"customer found the card"}' PORTAL)" 200
  echo "   DEMO_CARD $ref cancelled"
fi

echo "4b. My services: a large fine for an instalment plan (VF-071 minimum 5,000) and a service centre with slots (CC-090)"
catalogue DEMO_OVERLOAD 600000 0 "حمولة زائدة على شاحنة" "Truck over the permitted load"
open_overload=$(curl -sf -H "Authorization: Bearer $MAKER" "$VIO/v1/violations?fileNumber=$FILE&status=OPEN&limit=500" \
  | "$PY" -c "import json,sys; print(sum(1 for v in json.load(sys.stdin)['items'] if v['catalogueCode']=='DEMO_OVERLOAD'))")
if [[ "$open_overload" == 0 ]]; then
  at=$("$PY" -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=3)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
  need "$(post "$MAKER" "$VIO/v1/violations" "{\"catalogueCode\":\"DEMO_OVERLOAD\",\"occurredAt\":\"$at\",\"region\":\"DXB\",\"location\":\"Jebel Ali Port, gate 4\",\"plate\":\"A 12345\",\"ownerFileNumber\":\"$FILE\",\"driverFileNumber\":\"$FILE\",\"source\":\"OFFICER\"}" FIELD)" 201
  echo "   DEMO_OVERLOAD fine (AED 6,000, no points) recorded"
fi
APPT=http://localhost:8092
hours=$("$PY" -c "import json; print(json.dumps({d: [{'opens': '08:00', 'closes': '20:00'}] for d in ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY']}))")
need "$(post "$MAKER" "$APPT/v1/appointment-centres" "{\"code\":\"DEMO_DXB_HQ\",\"region\":\"DXB\",\"zone\":\"Asia/Dubai\",\"weeklyHours\":$hours,\"active\":true}")" 200
need "$(post "$MAKER" "$APPT/v1/appointment-resources" '{"code":"DEMO_DXB_HQ_R1","centreCode":"DEMO_DXB_HQ","type":"EXAMINER","name":"Counter 1","services":["DEMO_EYE_TEST"],"capacity":2,"active":true}')" 200
need "$(post "$MAKER" "$APPT/v1/appointment-service-settings" '{"serviceCode":"DEMO_EYE_TEST","slotMinutes":30,"horizonDays":30,"minLeadMinutes":60,"maxChanges":2,"noShowLimit":2,"noShowWindowDays":90,"penaltyDays":30}')" 200
echo "   centre DEMO_DXB_HQ (08:00-20:00, 2 counters) offers DEMO_EYE_TEST in 30-minute slots"

echo "4c. Back office: a review workflow with open cases in the case-officer queue (CC-060, CC-063)"
WF=http://localhost:8088
need "$(post "$MAKER" "$WF/v1/workflow-definitions" '{"code":"WF_DEMO_REVIEW","serviceCode":"DEMO_ADDRESS","nameNl":"مراجعة طلب","nameEn":"Application review","firstStep":"REVIEW","steps":[{"code":"REVIEW","kind":"REVIEW","nameNl":"مراجعة","nameEn":"Review","role":"CASE_OFFICER","assignment":"MANUAL","sla":"PT24H","escalationRole":"CASE_SUPERVISOR","outcomes":{"APPROVED":"END","REJECTED":"END"}}],"reason":"demo workflow"}')" 201 409
open_cases=$(curl -sf -H "Authorization: Bearer $CHECKER" "$WF/v1/work-items?list=TEAM&limit=200" | "$PY" -c "import json,sys; print(sum(1 for i in json.load(sys.stdin)['items'] if i['status'] in ('OPEN','IN_PROGRESS','ASSIGNED')))" 2>/dev/null || echo 0)
for n in $(seq "$((open_cases + 1))" 4); do
  need "$(post "$MAKER" "$WF/v1/work-items" "{\"workflowCode\":\"WF_DEMO_REVIEW\",\"reference\":\"DEMO-CASE-$(date +%s)-$n\",\"serviceCode\":\"DEMO_ADDRESS\",\"region\":\"DXB\"}")" 201
done
echo "   WF_DEMO_REVIEW: at least 4 open cases for the case officer (dev.checker)"

echo "5. Module tour set-up (configuration each module needs once; nothing is created twice)"
FEES=http://localhost:8086; VEH=http://localhost:8095; PERMIT=http://localhost:8102; GEO=http://localhost:8103
RENT=http://localhost:8101; XB=http://localhost:8106
quote=$(curl -s -H "Authorization: Bearer $MAKER" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' "$FEES/v1/quotes" \
  -d '{"serviceCode":"VEH_FIRST_REG","region":"DXB","customerCategory":"CITIZEN","subjectCategory":"PRIVATE"}' \
  | "$PY" -c "import json,sys; print(json.load(sys.stdin).get('total', 0))" 2>/dev/null || echo 0)
if [[ "$quote" == 0 ]]; then # first-registration fee, maker then checker (CC-114)
  res=$(post "$MAKER" "$FEES/v1/fee-versions" "{\"feeCode\":\"VEH_FIRST_REG_FEE\",\"serviceCode\":\"VEH_FIRST_REG\",\"nameNl\":\"رسم التسجيل\",\"nameEn\":\"First registration fee\",\"financialItem\":\"REV_VEHICLE_REG\",\"beneficiary\":\"AUTHORITY\",\"method\":{\"type\":\"FIXED\",\"amount\":40000},\"effectiveFrom\":\"$today\",\"reason\":\"demo vehicle fee\"}")
  need "$res" 201
  need "$(post "$CHECKER" "$FEES/v1/fee-versions/$(body_of "$res" | json_field "['id']")/approvals" '{"reason":"checked"}')" 200
fi
# The same plate series as smoke step 36 (an upsert), so a second series never changes which plates smoke expects.
need "$(post "$CHECKER" "$VEH/v1/plate-series" '{"code":"SMK_DXB_PRIVATE","region":"DXB","plateCategory":"PRIVATE","colourCode":"WHITE","design":"STANDARD","prefix":"S","firstNumber":1,"lastNumber":99999,"premiumPattern":"^(\\d)\\1+$","active":true}')" 200
need "$(post "$CHECKER" "$PERMIT/v1/permit-types" '{"code":"DEMO_INSTRUCTOR","category":"INSTRUCTOR","licenceClass":"LIGHT","minimumDrivingYears":3,"minimumAge":21,"cleanRecordDays":365,"medicalFitRequired":true,"testRequired":true,"validityDays":365,"renewalWindowDays":30,"feeMinor":5000}')" 200
geo() { # parent type code name-en name-ar [extra-json] -> "<status> <id>"
  local parent=${1:+,\"parentId\":\"$1\"} r
  r=$(post "$MAKER" "$GEO/v1/geographic-nodes" "{\"type\":\"$2\",\"code\":\"$3\",\"nameEn\":\"$4\",\"nameAr\":\"$5\",\"region\":\"DXB\"$parent${6:-}}")
  printf '%s %s' "$(status_of "$r")" "$(body_of "$r" | json_field ".get('id','')" 2>/dev/null)"
}
read -r s id <<<"$(geo "" REGION DEMO_DXB "Dubai" "دبي")"
if [[ "$s" == 201 ]]; then # the whole chain once: region > city > area > street > road segment
  read -r s id <<<"$(geo "$id" CITY DEMO_DXB_CITY "Dubai City" "مدينة دبي")"
  read -r s id <<<"$(geo "$id" AREA DEMO_JAFZA "Jebel Ali" "جبل علي")"
  read -r s id <<<"$(geo "$id" STREET DEMO_E11 "Sheikh Zayed Road" "شارع الشيخ زايد")"
  read -r s id <<<"$(geo "$id" ROAD_SEGMENT DEMO_E11_S1 "E11 km 20-40" "إي 11 كم 20-40" ',"roadType":"ARTERIAL","laneCount":6,"direction":"FORWARD","owningAuthority":"ROADS","speedLimitKmh":120.0')"
  [[ "$s" == 201 ]] || { echo "FAIL: road segment DEMO_E11_S1 ($s)"; exit 1; }
fi
res=$(post "$MAKER" "$RENT/v1/rental-companies" '{"trafficFileNumber":"TF-DEMO-RENTAL","commercialLicence":"CL-DEMO-1","region":"DXB"}')
need "$res" 201 409
if [[ "$(status_of "$res")" == 201 ]]; then # a repeated branch answers 500, not 409: add it only with a new company
  need "$(post "$MAKER" "$RENT/v1/rental-companies/TF-DEMO-RENTAL/branches" '{"code":"AIRPORT","name":"Airport desk","region":"DXB"}')" 201
fi
proposal() { # result -> approved by the checker (four eyes)
  [[ "$(status_of "$1")" == 409 ]] && return 0
  need "$1" 202
  need "$(post "$CHECKER" "$XB/v1/config-proposals/$(body_of "$1" | json_field "['id']")/decisions" '{"approve":true,"reason":"agreement signed"}')" 200
}
if ! curl -sf -o /dev/null -H "Authorization: Bearer $MAKER" "$XB/v1/partner-states/OMN"; then
  proposal "$(post "$MAKER" "$XB/v1/partner-states" '{"code":"OMN","name":"Partner state (demo)","mode":"REALTIME","commissionBasisPoints":1000,"allowsAccident":false,"allowsPermit":false,"allowsLicence":false,"endpointUrl":"http://partner-state-mock:8080/xb/OMN","reason":"bilateral agreement"}')"
  proposal "$(post "$MAKER" "$XB/v1/partner-states/OMN/mappings" '{"domain":"VIOLATION","localCode":"DEMO_SPEED","externalCode":"OM_SPEED","reason":"code list v1"}')"
fi
echo "   fee VEH_FIRST_REG, plate series S, permit type DEMO_INSTRUCTOR, road segment DEMO_E11_S1, rental company TF-DEMO-RENTAL, partner state OMN"

echo "DEMO SEED OK — sign in as $NATIONAL_ID (password DEV_CUSTOMER_PASSWORD in infra/local/.env)"
