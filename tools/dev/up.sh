#!/usr/bin/env bash
# One-command local startup (BRD reader's guide: "README that starts the whole system with one command").
#   tools/dev/up.sh          build + start PostgreSQL (one per service), Keycloak (realm "itms"), iam-service,
#                            reference-data-service, transaction-service and hold-eligibility-service, the
#                            observability stack (OpenTelemetry collector, Tempo, Loki, Prometheus, Grafana; NFR-040),
#                            OpenBao issuing each service a short-lived, rotated database login (SEC-004, ADR-039),
#                            then grant the development roles through iam-service
#   tools/dev/up.sh down     stop the stack (data volumes kept)
#   ITMS_LOCAL_PROFILE=lite  light profile for 16 GB machines (infra/local/compose.lite.yaml, ADR-057): no Tempo,
#                            Loki or Grafana, a clamd stand-in instead of ClamAV, small JVM heaps. Use the same
#                            value for up.sh, up.sh down and smoke.sh.
# First run generates infra/local/.env with random development secrets (git-ignored; SEC-004). Missing keys are
# added to an existing .env.
# Keycloak only authenticates; roles and permissions are held and granted by iam-service (ADR-022).
set -euo pipefail
cd "$(dirname "$0")/../.."
ENV_FILE=infra/local/.env
COMPOSE_FILES=(-f infra/local/compose.yaml)
case "${ITMS_LOCAL_PROFILE:-full}" in
  full) ;;
  lite) COMPOSE_FILES+=(-f infra/local/compose.lite.yaml) ;;
  *) echo "ITMS_LOCAL_PROFILE must be full or lite, got: $ITMS_LOCAL_PROFILE" >&2; exit 2 ;;
esac
COMPOSE=(docker compose "${COMPOSE_FILES[@]}" --env-file "$ENV_FILE")
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python

echo "== Ensuring $ENV_FILE has every development secret"
"$PY" - "$ENV_FILE" <<'PYEOF'
import os, secrets, sys
path = sys.argv[1]
wanted = {
    "REFDATA_DB_USER": lambda: "refdata",
    "REFDATA_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "IAM_DB_USER": lambda: "iam",
    "IAM_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "TXN_DB_USER": lambda: "transaction",
    "TXN_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "HOLD_DB_USER": lambda: "hold",
    "HOLD_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "TF_DB_USER": lambda: "trafficfile",
    "TF_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "FEES_DB_USER": lambda: "fees",
    "FEES_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "RULES_DB_USER": lambda: "rules",
    "RULES_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "WORKFLOW_DB_USER": lambda: "workflow",
    "WORKFLOW_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "NOTIF_DB_USER": lambda: "notification",
    "NOTIF_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DOC_DB_USER": lambda: "document",
    "DOC_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DEV_MINIO_ACCESS_KEY": lambda: "itms-dev-" + secrets.token_hex(4),
    "DEV_MINIO_SECRET_KEY": lambda: secrets.token_urlsafe(24),
    "APPT_DB_USER": lambda: "appointment",
    "APPT_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "AUDIT_DB_USER": lambda: "audit",
    "AUDIT_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "PAY_DB_USER": lambda: "payment",
    "PAY_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DEV_PAYMENT_CALLBACK_KEY": lambda: secrets.token_hex(32),
    "VEH_DB_USER": lambda: "vehicle",
    "VEH_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "VIO_DB_USER": lambda: "violation",
    "VIO_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DEVICE_DB_USER": lambda: "device",
    "DEVICE_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "ACC_DB_USER": lambda: "accident",
    "ACC_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "RENT_DB_USER": lambda: "rental",
    "RENT_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "PERMIT_DB_USER": lambda: "permit",
    "PERMIT_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "GEO_DB_USER": lambda: "geo",
    "GEO_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "ROAD_PERMIT_DB_USER": lambda: "roadpermit",
    "ROAD_PERMIT_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "MARINE_DB_USER": lambda: "marine",
    "MARINE_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "XB_DB_USER": lambda: "crossborder",
    "XB_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "SEARCH_DB_USER": lambda: "search",
    "SEARCH_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "REPORTING_DB_USER": lambda: "reporting",
    "REPORTING_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "REPORTING_PSEUDONYM_KEY": lambda: secrets.token_urlsafe(48),
    "MIGRATION_DB_USER": lambda: "migration",
    "MIGRATION_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "AI_DB_USER": lambda: "ai",
    "AI_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "KC_ADMIN_USER": lambda: "kc-admin",
    "KC_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DEV_IAM_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(16),
    "DEV_MAKER_PASSWORD": lambda: secrets.token_urlsafe(16),
    "DEV_CHECKER_PASSWORD": lambda: secrets.token_urlsafe(16),
    "DEV_DIRECTORY_FEED_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_TRANSACTION_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_VEHICLE_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_VIOLATION_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_DEVICE_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_DOCUMENT_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_LICENSING_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_EXAMINATION_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_CUSTOMER_PASSWORD": lambda: secrets.token_urlsafe(16),
    "DEV_NATIONAL_ID_BROKER_SECRET": lambda: secrets.token_urlsafe(32),
    "LIC_DB_USER": lambda: "licensing",
    "LIC_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "EXAM_DB_USER": lambda: "examination",
    "EXAM_DB_PASSWORD": lambda: secrets.token_urlsafe(24),
    "DEV_SEARCH_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_REPORTING_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_MIGRATION_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "DEV_AI_SERVICE_SECRET": lambda: secrets.token_urlsafe(32),
    "GRAFANA_ADMIN_PASSWORD": lambda: secrets.token_urlsafe(16),
    "BAO_ROOT_TOKEN": lambda: secrets.token_urlsafe(24),
}
present = set()
if os.path.exists(path):
    with open(path, encoding="utf-8") as f:
        present = {line.split("=", 1)[0] for line in f if "=" in line and not line.startswith("#")}
else:
    with open(path, "w", encoding="utf-8") as f:
        f.write("# LOCAL DEVELOPMENT ONLY. Generated by tools/dev/up.sh. Never commit.\n")
with open(path, "a", encoding="utf-8") as f:
    for key, make in wanted.items():
        if key not in present:
            f.write(f"{key}={make()}\n")
PYEOF
set -a; source "$ENV_FILE"; set +a

export BAO_AGENT_TOKEN=${BAO_AGENT_TOKEN:-not-yet-issued} # minted below; compose needs a value to parse the file
if [[ "${1:-}" == "down" ]]; then
  "${COMPOSE[@]}" down
  exit 0
fi

echo "== Building service jars"
mvn -B -ntp -q package -DskipTests -pl services/iam-service,services/reference-data-service,services/transaction-service,services/hold-eligibility-service,services/traffic-file-service,services/fees-service,services/rules-service,services/workflow-service,services/notification-service,services/document-service,services/appointment-service,services/audit-service,services/payment-service,services/vehicle-service,services/violation-service,services/device-service,services/licensing-service,services/accident-service,services/rental-service,services/permit-service,services/geo-service,services/road-permit-service,services/marine-service,services/crossborder-service,services/search-service,services/reporting-service,services/examination-service,services/migration-service,services/ai-service -am

echo "== Starting the databases and OpenBao"
"${COMPOSE[@]}" up -d --wait postgres iam-postgres transaction-postgres hold-postgres traffic-file-postgres fees-postgres rules-postgres workflow-postgres notification-postgres document-postgres appointment-postgres audit-postgres payment-postgres vehicle-postgres violation-postgres device-postgres accident-postgres rental-postgres permit-postgres geo-postgres road-permit-postgres marine-postgres crossborder-postgres search-postgres reporting-postgres licensing-postgres examination-postgres migration-postgres ai-postgres openbao

echo "== Database owner role itms_owner (ADR-039)"
for db in "postgres refdata $REFDATA_DB_USER" "iam-postgres iam $IAM_DB_USER" \
          "transaction-postgres transaction $TXN_DB_USER" "hold-postgres hold $HOLD_DB_USER" \
          "traffic-file-postgres trafficfile $TF_DB_USER" "fees-postgres fees $FEES_DB_USER" "rules-postgres rules $RULES_DB_USER" "workflow-postgres workflow $WORKFLOW_DB_USER" "notification-postgres notification $NOTIF_DB_USER" "document-postgres document $DOC_DB_USER" "appointment-postgres appointment $APPT_DB_USER" "audit-postgres audit $AUDIT_DB_USER" "payment-postgres payment $PAY_DB_USER" "vehicle-postgres vehicle $VEH_DB_USER" "violation-postgres violation $VIO_DB_USER" "device-postgres device $DEVICE_DB_USER" "accident-postgres accident $ACC_DB_USER" "rental-postgres rental $RENT_DB_USER" "permit-postgres permit $PERMIT_DB_USER" "geo-postgres geo $GEO_DB_USER" "road-permit-postgres roadpermit $ROAD_PERMIT_DB_USER" "marine-postgres marine $MARINE_DB_USER" "crossborder-postgres crossborder $XB_DB_USER" "search-postgres search $SEARCH_DB_USER" "reporting-postgres reporting $REPORTING_DB_USER" "licensing-postgres licensing $LIC_DB_USER" "examination-postgres examination $EXAM_DB_USER" "migration-postgres migration $MIGRATION_DB_USER" "ai-postgres ai $AI_DB_USER"; do
  set -- $db
  "${COMPOSE[@]}" exec -T "$1" psql -q -v ON_ERROR_STOP=1 -U "$3" -d "$2" < infra/local/db/itms-owner.sql
  echo "   $2: itms_owner owns the schema"
done

echo "== Configuring OpenBao: database logins per service, agent token"
BAO_AGENT_TOKEN=$({ printf "export BAO_TOKEN='%s' ITMS_DB_LOGIN_TTL='%s' ITMS_DB_LOGIN_MAX_TTL='%s'\n" \
      "$BAO_ROOT_TOKEN" "${ITMS_DB_LOGIN_TTL:-1h}" "${ITMS_DB_LOGIN_MAX_TTL:-24h}"
    printf "export IAM_DB_USER='%s' IAM_DB_PASSWORD='%s' REFDATA_DB_USER='%s' REFDATA_DB_PASSWORD='%s'\n" \
      "$IAM_DB_USER" "$IAM_DB_PASSWORD" "$REFDATA_DB_USER" "$REFDATA_DB_PASSWORD"
    printf "export TXN_DB_USER='%s' TXN_DB_PASSWORD='%s' HOLD_DB_USER='%s' HOLD_DB_PASSWORD='%s'\n" \
      "$TXN_DB_USER" "$TXN_DB_PASSWORD" "$HOLD_DB_USER" "$HOLD_DB_PASSWORD"
    printf "export TF_DB_USER='%s' TF_DB_PASSWORD='%s'\n" "$TF_DB_USER" "$TF_DB_PASSWORD"
    printf "export FEES_DB_USER='%s' FEES_DB_PASSWORD='%s'\n" "$FEES_DB_USER" "$FEES_DB_PASSWORD"
    printf "export RULES_DB_USER='%s' RULES_DB_PASSWORD='%s'\n" "$RULES_DB_USER" "$RULES_DB_PASSWORD"
    printf "export WORKFLOW_DB_USER='%s' WORKFLOW_DB_PASSWORD='%s'\n" "$WORKFLOW_DB_USER" "$WORKFLOW_DB_PASSWORD"
    printf "export NOTIF_DB_USER='%s' NOTIF_DB_PASSWORD='%s'\n" "$NOTIF_DB_USER" "$NOTIF_DB_PASSWORD"
    printf "export DOC_DB_USER='%s' DOC_DB_PASSWORD='%s'\n" "$DOC_DB_USER" "$DOC_DB_PASSWORD"
    printf "export APPT_DB_USER='%s' APPT_DB_PASSWORD='%s'\n" "$APPT_DB_USER" "$APPT_DB_PASSWORD"
    printf "export AUDIT_DB_USER='%s' AUDIT_DB_PASSWORD='%s'\n" "$AUDIT_DB_USER" "$AUDIT_DB_PASSWORD"
    printf "export PAY_DB_USER='%s' PAY_DB_PASSWORD='%s'\n" "$PAY_DB_USER" "$PAY_DB_PASSWORD"
    printf "export VEH_DB_USER='%s' VEH_DB_PASSWORD='%s'\n" "$VEH_DB_USER" "$VEH_DB_PASSWORD"
    printf "export VIO_DB_USER='%s' VIO_DB_PASSWORD='%s'\n" "$VIO_DB_USER" "$VIO_DB_PASSWORD"
    printf "export DEVICE_DB_USER='%s' DEVICE_DB_PASSWORD='%s'\n" "$DEVICE_DB_USER" "$DEVICE_DB_PASSWORD"
    printf "export ACC_DB_USER='%s' ACC_DB_PASSWORD='%s' RENT_DB_USER='%s' RENT_DB_PASSWORD='%s'\n" "$ACC_DB_USER" "$ACC_DB_PASSWORD" "$RENT_DB_USER" "$RENT_DB_PASSWORD"
    printf "export PERMIT_DB_USER='%s' PERMIT_DB_PASSWORD='%s' GEO_DB_USER='%s' GEO_DB_PASSWORD='%s'\n" "$PERMIT_DB_USER" "$PERMIT_DB_PASSWORD" "$GEO_DB_USER" "$GEO_DB_PASSWORD"
    printf "export ROAD_PERMIT_DB_USER='%s' ROAD_PERMIT_DB_PASSWORD='%s' MARINE_DB_USER='%s' MARINE_DB_PASSWORD='%s' XB_DB_USER='%s' XB_DB_PASSWORD='%s'\n" "$ROAD_PERMIT_DB_USER" "$ROAD_PERMIT_DB_PASSWORD" "$MARINE_DB_USER" "$MARINE_DB_PASSWORD" "$XB_DB_USER" "$XB_DB_PASSWORD"
    printf "export SEARCH_DB_USER='%s' SEARCH_DB_PASSWORD='%s' REPORTING_DB_USER='%s' REPORTING_DB_PASSWORD='%s'\n" "$SEARCH_DB_USER" "$SEARCH_DB_PASSWORD" "$REPORTING_DB_USER" "$REPORTING_DB_PASSWORD"
    printf "export MIGRATION_DB_USER='%s' MIGRATION_DB_PASSWORD='%s' AI_DB_USER='%s' AI_DB_PASSWORD='%s'\n" "$MIGRATION_DB_USER" "$MIGRATION_DB_PASSWORD" "$AI_DB_USER" "$AI_DB_PASSWORD"
    printf "export LIC_DB_USER='%s' LIC_DB_PASSWORD='%s' EXAM_DB_USER='%s' EXAM_DB_PASSWORD='%s'\n" "$LIC_DB_USER" "$LIC_DB_PASSWORD" "$EXAM_DB_USER" "$EXAM_DB_PASSWORD"
    cat infra/local/openbao/configure.sh
  } | "${COMPOSE[@]}" exec -T openbao sh -s | tail -n 1)
export BAO_AGENT_TOKEN
"${COMPOSE[@]}" up -d --force-recreate --wait bao-agent

echo "== Starting stack (this waits until every container is healthy)"
"${COMPOSE[@]}" up -d --build --wait

echo "== Setting development user passwords in Keycloak"
# MSYS_NO_PATHCONV stops Git Bash on Windows from rewriting the container path /opt/... (scoped to this call only).
KC=(env MSYS_NO_PATHCONV=1 docker compose "${COMPOSE_FILES[@]}" --env-file "$ENV_FILE" exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${KC[@]}" config credentials --server http://localhost:8080 --realm master \
  --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null
"${KC[@]}" set-password -r itms --username dev.iam-admin --new-password "$DEV_IAM_ADMIN_PASSWORD"
"${KC[@]}" set-password -r itms --username dev.maker --new-password "$DEV_MAKER_PASSWORD"
"${KC[@]}" set-password -r itms --username dev.checker --new-password "$DEV_CHECKER_PASSWORD"
# The directory feed (SEC-007) signs in with client credentials; its client id is fixed in the realm file.
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000001 -r itms -s "secret=$DEV_DIRECTORY_FEED_SECRET"
# transaction-service's own account for service-to-service calls (CC-032, ADR-034); compose passes it the same secret.
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000002 -r itms -s "secret=$DEV_TRANSACTION_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000003 -r itms -s "secret=$DEV_VEHICLE_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000004 -r itms -s "secret=$DEV_VIOLATION_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000005 -r itms -s "secret=$DEV_DEVICE_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000010 -r itms -s "secret=$DEV_DOCUMENT_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000011 -r itms -s "secret=$DEV_LICENSING_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000012 -r itms -s "secret=$DEV_EXAMINATION_SERVICE_SECRET"
# Increment 10-14 service clients
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000006 -r itms -s "secret=$DEV_SEARCH_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000007 -r itms -s "secret=$DEV_REPORTING_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000008 -r itms -s "secret=$DEV_MIGRATION_SERVICE_SECRET"
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000009 -r itms -s "secret=$DEV_AI_SERVICE_SECRET"

echo "== Customer sign-in through the mock national digital ID (ADR-067; LOCAL DEVELOPMENT ONLY)"
# The mock persons are those of the identity-register mock; one password for both, from $ENV_FILE.
"${KC[@]}" set-password -r national-id-mock --username 784199012345671 --new-password "$DEV_CUSTOMER_PASSWORD"
"${KC[@]}" set-password -r national-id-mock --username 784198812345672 --new-password "$DEV_CUSTOMER_PASSWORD"
"${KC[@]}" update clients/8e1d2b4f-6c3a-4d2e-9f0b-200000000001 -r national-id-mock -s "secret=$DEV_NATIONAL_ID_BROKER_SECRET"
kc_flow_id() { # alias -> id of the top-level flow, empty if missing
  "${KC[@]}" get authentication/flows -r itms --fields id,alias --format csv --noquotes | tr -d '\r' \
    | awk -F, -v a="$1" '$2 == a {print $1}'
}
kc_flow() { # alias provider requirement [config-alias config-json]: a top-level flow with one execution
  [[ -z "$(kc_flow_id "$1")" ]] || return 0
  "${KC[@]}" create authentication/flows -r itms -s alias="$1" -s providerId=basic-flow -s topLevel=true \
    -s builtIn=false -s "description=ADR-067" >/dev/null
  "${KC[@]}" create "authentication/flows/$1/executions/execution" -r itms -s provider="$2" >/dev/null
  local id
  id=$("${KC[@]}" get "authentication/flows/$1/executions" -r itms --fields id,providerId --format csv --noquotes \
    | tr -d '\r' | awk -F, -v p="$2" '$2 == p {print $1}')
  "${KC[@]}" update "authentication/flows/$1/executions" -r itms -b "{\"id\":\"$id\",\"requirement\":\"$3\"}"
  [[ -z "${4:-}" ]] || "${KC[@]}" create "authentication/executions/$id/config" -r itms \
    -b "{\"alias\":\"$4\",\"config\":$5}" >/dev/null
}
# First sign-in creates the customer's account (username = national id); never links to an existing account.
kc_flow customer-first-login idp-create-user-if-unique REQUIRED
# itms-portal has no password form: every sign-in goes to the national digital ID.
kc_flow customer-browser identity-provider-redirector REQUIRED national-id-only '{"defaultProvider":"national-id"}'
if ! "${KC[@]}" get identity-provider/instances/national-id -r itms >/dev/null 2>&1; then
  "${KC[@]}" create identity-provider/instances -r itms -f - >/dev/null <<IDP
{"alias": "national-id", "displayName": "National digital ID (mock)", "providerId": "oidc", "enabled": true,
 "trustEmail": false, "storeToken": false, "linkOnly": false, "hideOnLogin": true,
 "firstBrokerLoginFlowAlias": "customer-first-login",
 "config": {"issuer": "http://localhost:8180/realms/national-id-mock",
  "authorizationUrl": "http://localhost:8180/realms/national-id-mock/protocol/openid-connect/auth",
  "tokenUrl": "http://localhost:8080/realms/national-id-mock/protocol/openid-connect/token",
  "jwksUrl": "http://localhost:8080/realms/national-id-mock/protocol/openid-connect/certs",
  "logoutUrl": "http://localhost:8180/realms/national-id-mock/protocol/openid-connect/logout",
  "useJwksUrl": "true", "validateSignature": "true", "disableUserInfo": "true", "backchannelSupported": "false",
  "clientId": "itms-broker", "clientSecret": "$DEV_NATIONAL_ID_BROKER_SECRET", "clientAuthMethod": "client_secret_post",
  "defaultScope": "openid", "pkceEnabled": "true", "pkceMethod": "S256", "syncMode": "FORCE"}}
IDP
  "${KC[@]}" create identity-provider/instances/national-id/mappers -r itms -b '{"name": "username is the national id",
    "identityProviderAlias": "national-id", "identityProviderMapper": "oidc-username-idp-mapper",
    "config": {"template": "${CLAIM.national_id}", "target": "LOCAL", "syncMode": "FORCE"}}' >/dev/null
else
  "${KC[@]}" update identity-provider/instances/national-id -r itms -s "config.clientSecret=$DEV_NATIONAL_ID_BROKER_SECRET"
fi
"${KC[@]}" update clients/7d0c1a3e-5b2f-4c1d-9e8a-100000000020 -r itms \
  -s "authenticationFlowBindingOverrides.browser=$(kc_flow_id customer-browser)"
kc_exec_id() { # realm flow match-field value -> execution id in that flow
  "${KC[@]}" get "authentication/flows/$2/executions" -r "$1" --fields id,providerId,displayName --format csv \
    --noquotes | tr -d '\r' | awk -F, -v f="$3" -v v="$4" '(f == "provider" && $2 == v) || (f == "name" && $3 == v) {print $1; exit}'
}
kc_require() { # realm flow execution-id requirement
  "${KC[@]}" update "authentication/flows/$2/executions" -r "$1" -b "{\"id\":\"$3\",\"requirement\":\"$4\"}"
}
kc_add() { # realm flow provider requirement [config-alias config-json]
  "${KC[@]}" create "authentication/flows/$2/executions/execution" -r "$1" -s provider="$3" >/dev/null
  local id; id=$(kc_exec_id "$1" "$2" provider "$3"); kc_require "$1" "$2" "$id" "$4"
  [[ -z "${5:-}" ]] || "${KC[@]}" create "authentication/executions/$id/config" -r "$1" \
    -b "{\"alias\":\"$5\",\"config\":$6}" >/dev/null
}
kc_subflow() { # realm parent alias requirement
  "${KC[@]}" create "authentication/flows/$2/executions/flow" -r "$1" -s alias="$3" -s type=basic-flow \
    -s provider=registration-page-form >/dev/null
  kc_require "$1" "$2" "$(kc_exec_id "$1" "$2" name "$3")" "$4"
}
# Step-up (SEC-002, Q-007): the national digital ID does the second factor. Its sign-in has two levels, password
# (acr national-id) and a one-time password enrolled on first use (acr otp, valid 5 minutes); ITMS forwards the
# portal's acr_values to it and passes the acr it asserts into the customer's token.
if ! "${KC[@]}" get authentication/flows -r national-id-mock --fields alias --format csv --noquotes | tr -d '\r' \
    | grep -qx national-id-sign-in; then
  "${KC[@]}" create authentication/flows -r national-id-mock -s alias=national-id-sign-in -s providerId=basic-flow \
    -s topLevel=true -s builtIn=false -s "description=ADR-067 c" >/dev/null
  kc_add national-id-mock national-id-sign-in auth-cookie ALTERNATIVE
  kc_subflow national-id-mock national-id-sign-in national-id-levels ALTERNATIVE
  kc_subflow national-id-mock national-id-levels national-id-level-1 CONDITIONAL
  kc_add national-id-mock national-id-level-1 conditional-level-of-authentication REQUIRED national-id-loa-1 \
    '{"loa-condition-level":"1","loa-max-age":"36000"}'
  kc_add national-id-mock national-id-level-1 auth-username-password-form REQUIRED
  kc_subflow national-id-mock national-id-levels national-id-level-2 CONDITIONAL
  kc_add national-id-mock national-id-level-2 conditional-level-of-authentication REQUIRED national-id-loa-2 \
    '{"loa-condition-level":"2","loa-max-age":"300"}'
  kc_add national-id-mock national-id-level-2 auth-otp-form REQUIRED
  "${KC[@]}" update realms/national-id-mock -s browserFlow=national-id-sign-in
fi
"${KC[@]}" update clients/8e1d2b4f-6c3a-4d2e-9f0b-200000000001 -r national-id-mock -f - <<'ACR'
{"attributes": {"acr.loa.map": "{\"national-id\":1,\"otp\":2}", "default.acr.values": "national-id",
 "post.logout.redirect.uris": "http://localhost:8180/realms/itms/broker/national-id/endpoint/logout_response"}}
ACR
"${KC[@]}" update identity-provider/instances/national-id -r itms -s config.forwardParameters=acr_values
if [[ -z "$("${KC[@]}" get identity-provider/instances/national-id/mappers -r itms --fields name --format csv \
    --noquotes | tr -d '\r' | grep -x 'national id acr')" ]]; then
  "${KC[@]}" create identity-provider/instances/national-id/mappers -r itms -b '{"name": "national id acr",
    "identityProviderAlias": "national-id", "identityProviderMapper": "oidc-user-session-note-idp-mapper",
    "config": {"claims": "[{\"key\":\"acr\",\"value\":\".*\"}]", "are.claim.values.regex": "true",
    "syncMode": "FORCE"}}' >/dev/null
fi
# itms-portal tokens carry the national ID's acr, not the itms realm's own: the built-in acr scope is removed.
PORTAL=7d0c1a3e-5b2f-4c1d-9e8a-100000000020
acr_scope=$("${KC[@]}" get "clients/$PORTAL/default-client-scopes" -r itms --fields id,name --format csv --noquotes \
  | tr -d '\r' | awk -F, '$2 == "acr" {print $1}')
[[ -z "$acr_scope" ]] || "${KC[@]}" delete "clients/$PORTAL/default-client-scopes/$acr_scope" -r itms
if [[ -z "$("${KC[@]}" get "clients/$PORTAL/protocol-mappers/models" -r itms --fields name --format csv --noquotes \
    | tr -d '\r' | grep -x 'national id acr')" ]]; then
  "${KC[@]}" create "clients/$PORTAL/protocol-mappers/models" -r itms -b '{"name": "national id acr",
    "protocol": "openid-connect", "protocolMapper": "oidc-usersessionmodel-note-mapper", "config": {
    "user.session.note": "acr", "claim.name": "acr", "jsonType.label": "String", "access.token.claim": "true",
    "id.token.claim": "true", "introspection.token.claim": "true"}}' >/dev/null
fi

echo "== Granting development roles through iam-service (dev.iam-admin is the bootstrap administrator)"
IAM=http://localhost:8082
TOKEN_URL=http://localhost:8180/realms/itms/protocol/openid-connect/token
ADMIN_TOKEN=$(curl -sf "$TOKEN_URL" -d grant_type=password -d client_id=itms-dev-cli -d scope=openid \
  --data-urlencode "username=dev.iam-admin" --data-urlencode "password=$DEV_IAM_ADMIN_PASSWORD" \
  | "$PY" -c "import json,sys; print(json.load(sys.stdin)['access_token'])")
grant() { # subject username role
  local status
  status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$IAM/v1/role-assignments" \
    -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' -H 'X-Channel: BACKOFFICE' \
    -d "{\"subjectId\":\"$1\",\"subjectUsername\":\"$2\",\"roleCode\":\"$3\",\"scopes\":{\"regions\":[\"*\"],\"centres\":[\"*\"],\"modules\":[\"*\"]},\"reason\":\"local development user\"}")
  case "$status" in
    201) echo "   $2 -> $3 (assigned)" ;;
    409) echo "   $2 -> $3 (already assigned)" ;;
    *) echo "FAIL: assigning $3 to $2 returned HTTP $status"; exit 1 ;;
  esac
}
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker REFDATA_MAKER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker REFDATA_CHECKER
# Service catalogue maker-checker (CC-020, CC-026, CC-114; ADR-028).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CATALOGUE_MAKER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CATALOGUE_CHECKER
# Counter staff for transactions (CC-021..CC-027; ADR-029).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker TRANSACTION_OFFICER
# Holds (CC-030; ADR-033): dev.checker places, reads and lifts holds.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker HOLD_OFFICER
# Hold overrides for one transaction (CC-034; ADR-035): dev.maker is the supervisor, so it is not the placer.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker HOLD_SUPERVISOR
# Counter eligibility check (CC-032): the demo control room asks whether a service is blocked (demo D2.3).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker ELIGIBILITY_CHECKER
# Traffic files (DL-001, DL-005; ADR-041): dev.checker opens and updates files; dev.maker sees sensitive data.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker TRAFFIC_FILE_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker TRAFFIC_FILE_SENSITIVE_READER
# Ban list (DL-013; ADR-043): dev.checker places, reads and lifts bans.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker BAN_LIST_OFFICER
# Merging duplicate traffic files (DL-009; ADR-044): dev.maker is the supervisor.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker TRAFFIC_FILE_SUPERVISOR
# Identity register notifications (DL-010): locally dev.maker stands in for the register adapter's account,
# which gets its own Keycloak client when the real adapter is built (INT-004).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker IDENTITY_REGISTER_FEED
# Fees maker-checker (CC-046; ADR-046): dev.maker submits, dev.checker approves.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker FEE_MAKER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker FEE_CHECKER
# Business rules maker-checker (CC-051; ADR-048): dev.maker submits and tests, dev.checker approves.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker RULE_MAKER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker RULE_CHECKER
# Workflow (CC-060..CC-065; ADR-049): dev.maker publishes workflows, supervises and starts cases locally; dev.checker works cases (the administrator may not grant itself roles, CC-004).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker WORKFLOW_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CASE_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CASE_SUPERVISOR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker WORKFLOW_STARTER
# Notifications (CC-070..CC-074; ADR-050): dev.maker administers templates and sends; dev.checker is customer support.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker NOTIFICATION_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker NOTIFICATION_SENDER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker NOTIFICATION_SUPPORT
# Documents and certificates (CC-080..CC-085; ADR-051): dev.maker receives documents and issues certificates; dev.checker administers certificates and reads sensitive documents.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker DOCUMENT_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CERTIFICATE_ISSUER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CERTIFICATE_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker DOCUMENT_SENSITIVE_READER
# Appointments (CC-090..CC-093; ADR-052): dev.maker administers calendars; dev.checker is a counter agent.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker APPOINTMENT_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker APPOINTMENT_AGENT
# Central audit trail (CC-120..CC-122; ADR-053): dev.checker is the auditor.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker AUDITOR
# Payments (FN-001..FN-005; ADR-054): dev.maker registers payables, is a cashier and relays gateway callbacks locally; dev.checker supervises cashiers.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker PAYABLE_REGISTRAR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CASHIER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker PAYMENT_GATEWAY_ADAPTER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CASHIER_SUPERVISOR
# Vehicles (VL-*; ADR-055): dev.maker records vehicles and registers them; dev.checker manages plate series and records inspections locally.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker VEHICLE_OFFICER
# Vehicle transactions such as certificates (VL-020; smoke step 50).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker VEHICLE_SERVICE_OFFICER
# Customs/dealer certificate feed (VL-002, VL-010): locally dev.maker stands in for the customs adapter's account.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CUSTOMS_INTEGRATION
# Own instalment plans for open fines (VF-071, VF-073).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker INSTALMENT_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker PLATE_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker INSPECTION_CENTRE
# Violations (VF-*; ADR-056): dev.maker records violations and keeps the catalogue; dev.checker cancels (supervisor).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker VIOLATION_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker VIOLATION_CATALOGUE_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker VIOLATION_SUPERVISOR
# dev.checker is also the development access reviewer ("manager", SEC-007, ADR-026); dev.iam-admin, the IT
# administrator, opens and closes reviews.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker ACCESS_REVIEWER
# Service-account subject of the itms-directory-feed client (fixed in the realm file); deactivates leavers (SEC-007).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000010 service-account-itms-directory-feed DIRECTORY_FEED
# Service-account subject of the itms-transaction-service client; checks eligibility at submission (CC-032, ADR-034).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000011 service-account-itms-transaction-service ELIGIBILITY_CHECKER
# vehicle-service registers registration fees as payables at payment-service (ADR-055).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000012 service-account-itms-vehicle-service PAYABLE_REGISTRAR
# violation-service registers fines as payables and places licence suspension holds (VF-015, VF-032; ADR-056).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service PAYABLE_REGISTRAR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service SUSPENSION_HOLD_PLACER
# violation-service places and lifts UNPAID_FINES holds on violations left unpaid (VF-023; ADR-058).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service UNPAID_FINE_HOLD_MANAGER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service VIOLATION_PAYMENT_HOLDER
# violation-service finds the local vehicle and owner of a partner-state violation by plate (XB-002).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service VEHICLE_READER
# violation-service asks rental-service which contract covered a vehicle (TR-032, VF-014, UAT-2).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000013 service-account-itms-violation-service RENTAL_COVERAGE_READER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000014 service-account-itms-device-service DEVICE_VIOLATION_RECORDER
# document-service prints the holder's name on the certificates it renders for other modules (CC-082).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000020 service-account-itms-document-service TRAFFIC_FILE_READER_SERVICE
# licensing-service registers licence fees as payables, checks holds and reads the holder's file (DL-050, DL-062).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000021 service-account-itms-licensing-service PAYABLE_REGISTRAR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000021 service-account-itms-licensing-service ELIGIBILITY_CHECKER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000021 service-account-itms-licensing-service TRAFFIC_FILE_READER_SERVICE
# examination-service hands passed learning files to licensing (DL-036).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000022 service-account-itms-examination-service EXAMINATION_TRANSFER_SENDER
# Licences and examinations (DL-*): dev.maker runs files, schools and bookings; dev.checker heads the committee,
# examines and supervises; licence officers on both.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker LICENSING_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker LICENSING_SUPERVISOR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker EXAM_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker DRIVING_SCHOOL_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker LEARNING_FILE_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker EXAM_COMMITTEE_HEAD
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker EXAM_SUPERVISOR
# Enforcement device registry and manual capture quality queue (VF-010, VF-011, VF-080, VF-081).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker DEVICE_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker DEVICE_GATEWAY
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker DEVICE_REVIEWER
# Accident reporting and rental-company partner API access (AC-001..AC-006, TR-020..TR-033).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker ACCIDENT_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker ACCIDENT_INVESTIGATOR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker REPAIR_PERMIT_VERIFIER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker RENTAL_PARTNER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker ACCIDENT_PARTY
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker ACCIDENT_SUPERVISOR
# Permit lifecycle, geography, road authorities, marine and partner-state exchange (TR-001..TR-008, RE-*, XB-*).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker TRAINING_PERMIT_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker TRAINING_PERMIT_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker GEO_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker ROAD_PERMIT_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker ROAD_AUTHORITY_APPROVER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker ROAD_PERMIT_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker MARINE_REGISTRY_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker MARINE_INSPECTOR
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CROSSBORDER_ADMIN
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CROSSBORDER_EXCHANGER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CROSSBORDER_OBJECTION_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CROSSBORDER_SETTLEMENT_AUDITOR
# Partner settings and mappings: dev.maker proposes, dev.checker approves (XB-007); data exchange and statements (XB-004, XB-006).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CROSSBORDER_APPROVER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker CROSSBORDER_DATA_OFFICER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker CROSSBORDER_SETTLEMENT_OFFICER
# vehicle-service asks hold-eligibility-service whether a renewal is blocked (VL-011, VF-023; ADR-058).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000012 service-account-itms-vehicle-service ELIGIBILITY_CHECKER

# Increment 10: unified search (CC-100); what a user finds still follows their own read grants.
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000002 dev.maker SEARCH_USER
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker SEARCH_USER
# Increment 11: reports within the assignment's regions (RPT-004).
grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000003 dev.checker REPORT_VIEWER
# Increments 12-13: no iam migration defines these roles yet; the grants wait until migration-service and
# ai-service pass the module gate (the project guide Â§2).
# Increment 12: migration-service (DAT-*) - national scope for data migration
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000017 service-account-itms-migration-service MIGRATION_EXECUTE
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000017 service-account-itms-migration-service RECONCILIATION_EXECUTE
# Increment 13: ai-service (AI-*) - national scope for AI/ML operations
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000018 service-account-itms-ai-service AI_INFERENCE
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000018 service-account-itms-ai-service AI_MODEL_MANAGE
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000018 service-account-itms-ai-service AI_USE_CASE_MANAGE
# grant 7d0c1a3e-5b2f-4c1d-9e8a-000000000018 service-account-itms-ai-service AI_KILL_SWITCH

cat <<EOF

ITMS local stack is up:
  API gateway (APISIX)    http://localhost:9080/v1/...               (every service's /v1/ API; bearer token required)
  iam-service             http://localhost:8082/v1/roles             (bearer token required)
  reference-data-service  http://localhost:8081/v1/reference-lists   (bearer token required)
  transaction-service     http://localhost:8083/v1/services          (bearer token required)
  hold-eligibility-service http://localhost:8084/v1/holds            (bearer token required)
  traffic-file-service    http://localhost:8085/v1/traffic-files/... (bearer token required)
  fees-service            http://localhost:8086                     (bearer token required)
  rules-service           http://localhost:8087                     (bearer token required)
  workflow-service        http://localhost:8088                     (bearer token required)
  notification-service    http://localhost:8089                     (bearer token required)
  document-service        http://localhost:8091                     (bearer token required)
  appointment-service     http://localhost:8092                     (bearer token required)
  audit-service           http://localhost:8093                     (bearer token required)
  payment-service         http://localhost:8094                     (bearer token required)
  vehicle-service         http://localhost:8095                     (bearer token required)
  violation-service       http://localhost:8096                     (bearer token required)
  device-service          http://localhost:8097                     (bearer token required)
  accident-service        http://localhost:8100                     (bearer token required)
  rental-service          http://localhost:8101                     (bearer token required)
  permit-service           http://localhost:8102                     (bearer token required)
  geo-service              http://localhost:8103                     (bearer token required)
  road-permit-service      http://localhost:8104                     (bearer token required)
  marine-service           http://localhost:8105                     (bearer token required)
  crossborder-service      http://localhost:8106                     (bearer token required)
  search-service           http://localhost:8107                     (bearer token required)
  reporting-service        http://localhost:8108                     (bearer token required)
  licensing-service        http://localhost:8098                     (bearer token required)
  examination-service      http://localhost:8099                     (bearer token required)
  migration-service        http://localhost:8109                     (bearer token required)
  ai-service               http://localhost:8110                     (bearer token required)
  Keycloak                http://localhost:8180   (realm: itms; users dev.iam-admin / dev.maker / dev.checker;
                                                   passwords in $ENV_FILE; clients itms-directory-feed and
                                                   itms-transaction-service use DEV_DIRECTORY_FEED_SECRET and
                                                   DEV_TRANSACTION_SERVICE_SECRET; customers sign in to
                                                   client itms-portal through realm national-id-mock as
                                                   784199012345671 / 784198812345672, DEV_CUSTOMER_PASSWORD)
  Grafana                 http://localhost:3000   (user admin; password GRAFANA_ADMIN_PASSWORD in $ENV_FILE;
                                                   metrics, logs and traces of every service)
  Prometheus              http://localhost:9090
Profile:                  ${ITMS_LOCAL_PROFILE:-full} (lite: no Grafana, Tempo or Loki; clamd stand-in, not ClamAV)
Smoke test:               tools/dev/smoke.sh
Stop:                     tools/dev/up.sh down
EOF
