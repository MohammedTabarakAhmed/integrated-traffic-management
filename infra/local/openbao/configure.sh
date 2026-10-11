#!/bin/sh
# Configures the local OpenBao (SEC-004, ADR-039); run by tools/dev/up.sh inside the openbao container, with the
# database bootstrap credentials on stdin-provided environment. Idempotent: the dev server keeps no state across
# restarts, so this runs on every start. Prints the agent's token as the last line.
#
# For each service: a database connection (the bootstrap superuser is used only by OpenBao) and a role that creates
# a login per lease, member of itms_owner (infra/local/db/itms-owner.sql), valid until the lease ends.
set -eu
export BAO_ADDR=http://127.0.0.1:8200

bao secrets list -format=json | grep -q '"database/"' || bao secrets enable database >/dev/null

configure() { # service host database user password
  bao write "database/config/$1" plugin_name=postgresql-database-plugin \
    connection_url="postgresql://{{username}}:{{password}}@$2:5432/$3?sslmode=disable" \
    allowed_roles="$1" username="$4" password="$5" verify_connection=true >/dev/null
  bao write "database/roles/$1" db_name="$1" \
    creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT IN ROLE itms_owner;" \
    revocation_statements="DROP ROLE IF EXISTS \"{{name}}\";" \
    default_ttl="$ITMS_DB_LOGIN_TTL" max_ttl="$ITMS_DB_LOGIN_MAX_TTL" >/dev/null
}
configure iam-service iam-postgres iam "$IAM_DB_USER" "$IAM_DB_PASSWORD"
configure reference-data-service postgres refdata "$REFDATA_DB_USER" "$REFDATA_DB_PASSWORD"
configure transaction-service transaction-postgres transaction "$TXN_DB_USER" "$TXN_DB_PASSWORD"
configure hold-eligibility-service hold-postgres hold "$HOLD_DB_USER" "$HOLD_DB_PASSWORD"
configure traffic-file-service traffic-file-postgres trafficfile "$TF_DB_USER" "$TF_DB_PASSWORD"
configure fees-service fees-postgres fees "$FEES_DB_USER" "$FEES_DB_PASSWORD"
configure rules-service rules-postgres rules "$RULES_DB_USER" "$RULES_DB_PASSWORD"
configure workflow-service workflow-postgres workflow "$WORKFLOW_DB_USER" "$WORKFLOW_DB_PASSWORD"
configure notification-service notification-postgres notification "$NOTIF_DB_USER" "$NOTIF_DB_PASSWORD"
configure document-service document-postgres document "$DOC_DB_USER" "$DOC_DB_PASSWORD"
configure appointment-service appointment-postgres appointment "$APPT_DB_USER" "$APPT_DB_PASSWORD"
configure audit-service audit-postgres audit "$AUDIT_DB_USER" "$AUDIT_DB_PASSWORD"
configure payment-service payment-postgres payment "$PAY_DB_USER" "$PAY_DB_PASSWORD"
configure vehicle-service vehicle-postgres vehicle "$VEH_DB_USER" "$VEH_DB_PASSWORD"
configure violation-service violation-postgres violation "$VIO_DB_USER" "$VIO_DB_PASSWORD"
configure device-service device-postgres device "$DEVICE_DB_USER" "$DEVICE_DB_PASSWORD"
configure accident-service accident-postgres accident "$ACC_DB_USER" "$ACC_DB_PASSWORD"
configure rental-service rental-postgres rental "$RENT_DB_USER" "$RENT_DB_PASSWORD"
configure permit-service permit-postgres permit "$PERMIT_DB_USER" "$PERMIT_DB_PASSWORD"
configure geo-service geo-postgres geo "$GEO_DB_USER" "$GEO_DB_PASSWORD"
configure road-permit-service road-permit-postgres roadpermit "$ROAD_PERMIT_DB_USER" "$ROAD_PERMIT_DB_PASSWORD"
configure marine-service marine-postgres marine "$MARINE_DB_USER" "$MARINE_DB_PASSWORD"
configure crossborder-service crossborder-postgres crossborder "$XB_DB_USER" "$XB_DB_PASSWORD"
configure search-service search-postgres search "$SEARCH_DB_USER" "$SEARCH_DB_PASSWORD"

# The agent may only read database logins.
printf 'path "database/creds/*" {\n  capabilities = ["read"]\n}\n' | bao policy write itms-db-agent - >/dev/null
bao token create -policy=itms-db-agent -period=24h -orphan -field=token
