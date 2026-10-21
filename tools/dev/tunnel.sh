#!/usr/bin/env bash
# Lets a remote tester use the demo portal through a tunnel (ngrok in front of the Vite dev server on :5173).
# Keycloak keeps its issuer (http://localhost:8180, what every service checks); the portal's dev proxy carries the
# sign-in pages to the tunnel address. tools/dev/live.sh runs all of this in one go; by hand:
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/tunnel.sh --policy                                   tunnel key policy file
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/tunnel.sh https://<name>.ngrok-free.app            register the address
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/tunnel.sh --remove https://<name>.ngrok-free.app   unregister it
# The address goes into the itms-portal client (redirect URI, web origin, post-logout redirect). Run it again after
# Keycloak is recreated (the realm is re-imported without the address). The tunnel key (link ?key=...) is
# DEMO_TUNNEL_PASSWORD in infra/local/.env, made once and kept; the ngrok traffic policy that asks for it is written
# to the git-ignored infra/local/secrets/.
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
POLICY=infra/local/secrets/ngrok-policy.yml

# The tunnel key: made once (appended to .env like up.sh does), then the same every time.
"$PY" - "$ENV_FILE" <<'PYEOF'
import secrets, sys
path = sys.argv[1]
with open(path, encoding="utf-8") as f:
    if any(line.startswith("DEMO_TUNNEL_PASSWORD=") for line in f):
        sys.exit(0)
with open(path, "a", encoding="utf-8") as f:
    f.write(f"DEMO_TUNNEL_PASSWORD={secrets.token_urlsafe(12)}\n")
PYEOF
set -a; source <(tr -d '\r' < "$ENV_FILE"); set +a
mkdir -p "$(dirname "$POLICY")"
# A secret link, not a password prompt: the portal sends its own Authorization header (the sign-in token) on every API
# call, which an HTTP basic-auth prompt would clash with. Opening <url>/?key=<key> once sets a cookie (one day); a
# request without that cookie gets a short 401 page.
"$PY" - "$POLICY" "$DEMO_TUNNEL_PASSWORD" <<'PYEOF'
import sys
path, key = sys.argv[1], sys.argv[2]
page = ("<!doctype html><meta charset=utf-8><title>ITMS demo</title><body style='font:16px system-ui;"
        "background:#0b1020;color:#e6e9f2;display:grid;place-items:center;height:100vh;margin:0'>"
        "<div><h2>ITMS demo</h2><p>Open the full link you were sent (it ends with ?key=...).</p></div>")
policy = f'''on_http_request:
  - expressions:
      - "req.url.query_params['key'][0] == '{key}'"
    actions:
      - type: custom-response
        config:
          status_code: 200
          headers:
            content-type: "text/html; charset=utf-8"
            location: "/"
            set-cookie: "itms_gate={key}; Path=/; Max-Age=86400; SameSite=Lax"
          body: "<meta charset=utf-8><title>ITMS demo</title><body style='font:16px system-ui;background:#0b1020;color:#e6e9f2'><h2>ITMS demo</h2><p>Loading… <a href='/'>continue</a>.</p>"
  - expressions:
      - "!('itms_gate' in req.cookies && req.cookies['itms_gate'][0].value == '{key}')"
    actions:
      - type: custom-response
        config:
          status_code: 401
          headers:
            content-type: "text/html; charset=utf-8"
          body: "{page}"
'''
with open(path, "w", encoding="utf-8", newline="\n") as f:
    f.write(policy)
PYEOF
if [[ "${1:-}" == "--policy" ]]; then
  command -v cygpath >/dev/null && cygpath -w "$PWD/$POLICY" || echo "$PWD/$POLICY"
  exit 0
fi

mode=add
if [[ "${1:-}" == "--remove" ]]; then mode=remove; shift; fi
url=${1:-}
url=${url%/}
if [[ ! "$url" =~ ^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$ ]]; then
  echo "usage: tunnel.sh [--policy | [--remove] https://<tunnel host>]   (https, host only, no path)" >&2; exit 2
fi

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
echo "== $url $([[ "$mode" == add ]] && echo registered || echo removed) for the citizen sign-in"
