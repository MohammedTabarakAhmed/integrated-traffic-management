#!/usr/bin/env bash
# Lets a remote tester use the demo portal through a tunnel (for example ngrok in front of the Vite dev server on
# :5173). Keycloak keeps its issuer (http://localhost:8180, what every service checks); the portal's dev proxy carries
# the sign-in pages to the tunnel address. This script only registers that address with the itms-portal client
# (redirect URI, web origin, post-logout redirect) so the national-ID sign-in may return to it.
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/tunnel.sh https://<name>.ngrok-free.app            register
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/tunnel.sh --remove https://<name>.ngrok-free.app   unregister
# Run it again after Keycloak is recreated (the realm is re-imported without the address).
set -euo pipefail
cd "$(dirname "$0")/../.."
ENV_FILE=infra/local/.env
COMPOSE_FILES=(-f infra/local/compose.yaml)
case "${ITMS_LOCAL_PROFILE:-full}" in
  full) ;;
  lite) COMPOSE_FILES+=(-f infra/local/compose.lite.yaml) ;;
  *) echo "ITMS_LOCAL_PROFILE must be full or lite, got: $ITMS_LOCAL_PROFILE" >&2; exit 2 ;;
esac
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python

mode=add
if [[ "${1:-}" == "--remove" ]]; then mode=remove; shift; fi
url=${1:-}
url=${url%/}
if [[ ! "$url" =~ ^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; then
  echo "usage: tunnel.sh [--remove] https://<tunnel host>   (https, host only, no path)" >&2; exit 2
fi

set -a; source "$ENV_FILE"; set +a
KC=(env MSYS_NO_PATHCONV=1 docker compose "${COMPOSE_FILES[@]}" --env-file "$ENV_FILE" exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${KC[@]}" config credentials --server http://localhost:8080 --realm master \
  --user "$KC_ADMIN_USER" --password "$KC_ADMIN_PASSWORD" >/dev/null
PORTAL_CLIENT=7d0c1a3e-5b2f-4c1d-9e8a-100000000020 # itms-portal, fixed in infra/local/keycloak/itms-realm.json

current=$("${KC[@]}" get "clients/$PORTAL_CLIENT" -r itms)
change=$(printf '%s' "$current" | "$PY" -c '
import json, sys
c, url, mode = json.load(sys.stdin), sys.argv[1], sys.argv[2]
def edit(items, value):
    items = [x for x in items if x != value]
    return items + [value] if mode == "add" else items
redirects = edit(c.get("redirectUris", []), url + "/*")
origins = edit(c.get("webOrigins", []), url)
attrs = c.get("attributes", {})
logout = [x for x in attrs.get("post.logout.redirect.uris", "").split("##") if x]
logout = edit(logout, url + "/*")
print(json.dumps({"redirectUris": redirects, "webOrigins": origins,
                  "attributes": {"post.logout.redirect.uris": "##".join(logout)}}))
' "$url" "$mode")
printf '%s' "$change" | "${KC[@]}" update "clients/$PORTAL_CLIENT" -r itms -f -
"${KC[@]}" get "clients/$PORTAL_CLIENT" -r itms --fields redirectUris,webOrigins
# The portal signs staff in with the development passwords, so the tunnel asks for a user name and password first
# (ngrok traffic policy, basic-auth action). The file sits in a git-ignored secrets/ folder.
POLICY=infra/local/secrets/ngrok-policy.yml
if [[ "$mode" == add ]]; then
  mkdir -p "$(dirname "$POLICY")"
  pw=$("$PY" -c "import secrets; print(secrets.token_urlsafe(12))")
  printf 'on_http_request:\n  - actions:\n      - type: basic-auth\n        config:\n          credentials:\n            - "tester:%s"\n' "$pw" > "$POLICY"
  policy_path=$(command -v cygpath >/dev/null && cygpath -w "$PWD/$POLICY" || echo "$PWD/$POLICY")
  echo "== $url registered. Tunnel password: user tester, password $pw (send it to the tester separately)"
  echo "   1. in demo/portal (PowerShell):  \$env:__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS='${url#https://}'; npm run dev"
  echo "   2. ngrok http 5173 --url=$url --traffic-policy-file \"$policy_path\""
else
  rm -f "$POLICY"
  echo "== $url removed"
fi
