#!/usr/bin/env bash
# Puts the demo portal live for a remote tester in one command, and takes it down again (ngrok; the agent must already
# hold the account's authtoken: ngrok config add-authtoken ...). Needs the local stack up (up.sh, demo-seed.sh).
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/live.sh         start the portal if it is not running, open the tunnel,
#                                                          register its address for sign-in, print the link and accounts
#   ITMS_LOCAL_PROFILE=lite bash tools/dev/live.sh stop    close the tunnel and unregister its address
# Logs: infra/local/secrets/vite.log and ngrok.log (git-ignored).
set -euo pipefail
cd "$(dirname "$0")/../.."
ENV_FILE=infra/local/.env
STATE=infra/local/secrets
API=http://127.0.0.1:4040/api/tunnels # the ngrok agent's local API
PY=${PYTHON:-python3}; command -v "$PY" >/dev/null || PY=python
mkdir -p "$STATE"

public_url() { { curl -s "$API" 2>/dev/null || true; } | "$PY" -c "import json,sys
try: print(next(t['public_url'] for t in json.load(sys.stdin)['tunnels'] if t['public_url'].startswith('https://')))
except Exception: pass" ; }
stop_ngrok() { if command -v taskkill >/dev/null; then taskkill //IM ngrok.exe //F >/dev/null 2>&1 || true; else pkill -x ngrok || true; fi; }

if [[ "${1:-}" == "stop" ]]; then
  url=$(public_url); [[ -n "$url" ]] || url=$(cat "$STATE/live-url" 2>/dev/null || true)
  stop_ngrok
  [[ -n "$url" ]] && bash tools/dev/tunnel.sh --remove "$url"
  rm -f "$STATE/live-url"
  echo "== The demo is no longer live (the local portal keeps running at http://localhost:5173)"
  exit 0
fi

# The gateway answers 404 for an unknown certificate when the stack is up.
[[ "$(curl -s -o /dev/null -w '%{http_code}' 'http://localhost:9080/v1/certificate-verifications/X?code=ABCDEFGHIJ')" == 404 ]] \
  || { echo "The local stack is not up: run ITMS_LOCAL_PROFILE=lite bash tools/dev/up.sh and demo-seed.sh first." >&2; exit 1; }

echo "== Portal"
if [[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/)" != 200 ]]; then
  (cd demo/portal && nohup npm run dev > "../../$STATE/vite.log" 2>&1 &)
  for _ in $(seq 1 60); do [[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/)" == 200 ]] && break; sleep 1; done
fi
[[ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/)" == 200 ]] || { echo "The portal did not start; see $STATE/vite.log" >&2; exit 1; }
echo "   http://localhost:5173 is up"

echo "== Tunnel"
policy=$(bash tools/dev/tunnel.sh --policy)
url=$(public_url)
if [[ -z "$url" ]]; then
  nohup ngrok http 5173 --traffic-policy-file "$policy" --log stdout > "$STATE/ngrok.log" 2>&1 &
  for _ in $(seq 1 30); do url=$(public_url); [[ -n "$url" ]] && break; sleep 1; done
fi
[[ -n "$url" ]] || { echo "ngrok did not open a tunnel; see $STATE/ngrok.log" >&2; exit 1; }
echo "$url" > "$STATE/live-url"
bash tools/dev/tunnel.sh "$url"

set -a; source <(tr -d '\r' < "$ENV_FILE"); set +a
cat <<EOF

================================================================================
 ITMS demo is LIVE. Send the tester this exact link (the key lets them in for a day):
   $url/?key=$DEMO_TUNNEL_PASSWORD
================================================================================
 1. ngrok first shows a notice: click "Visit Site". Without the key: "Open the full link you were sent".
 2. Citizen    My dashboard > Sign in with National Digital ID
               ID: 784199012345671                       password: $DEV_CUSTOMER_PASSWORD
 3. Officer    dev.maker                                 password: $DEV_MAKER_PASSWORD
    Supervisor dev.checker                               password: $DEV_CHECKER_PASSWORD
    (most staff pages sign in by themselves; the Control room asks)
 Stop:  ITMS_LOCAL_PROFILE=lite bash tools/dev/live.sh stop
================================================================================
EOF
