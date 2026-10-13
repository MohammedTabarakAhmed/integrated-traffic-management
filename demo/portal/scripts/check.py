"""Demo check: signs in like the portal does (PKCE through itms and the mock national ID) and reads the customer's
dashboard APIs through the Vite dev server, so the proxy, the redirect URI and the data are all checked.

Run with the stack up, the seed loaded and `npm run dev` running:
    python demo/portal/scripts/check.py <national-id> <password>
"""
import base64, hashlib, html, http.cookiejar, json, re, secrets, sys, urllib.parse, urllib.request

PORTAL = "http://localhost:5173"
KC = "http://localhost:8180"
CB = PORTAL + "/"


class Callback(Exception):
    pass


class StopAtCallback(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if newurl.startswith(CB) and "code=" in newurl:
            raise Callback(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class LocalCookies(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):  # Keycloak marks cookies Secure; the local stack is plain HTTP
        return True


browser = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(LocalCookies())), StopAtCallback())


def sign_in(user, password):
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    q = urllib.parse.urlencode({"client_id": "itms-portal", "response_type": "code", "scope": "openid",
                                "redirect_uri": CB, "state": "s", "code_challenge": challenge,
                                "code_challenge_method": "S256"})
    try:
        page = browser.open(f"{KC}/realms/itms/protocol/openid-connect/auth?{q}").read().decode()
        action = html.unescape(re.search(r'<form[^>]*action="([^"]+)"', page).group(1))
        browser.open(action, urllib.parse.urlencode({"username": user, "password": password}).encode())
        sys.exit("FAIL: sign-in did not come back to the portal")
    except Callback as done:
        code = urllib.parse.parse_qs(urllib.parse.urlparse(str(done)).query)["code"][0]
    # Token exchange through the Vite proxy, as the browser does.
    body = urllib.parse.urlencode({"grant_type": "authorization_code", "client_id": "itms-portal", "code": code,
                                   "redirect_uri": CB, "code_verifier": verifier}).encode()
    return json.load(urllib.request.urlopen(f"{PORTAL}/realms/itms/protocol/openid-connect/token", body))["access_token"]


def get(token, path):
    req = urllib.request.Request(PORTAL + path, headers={"Authorization": f"Bearer {token}", "X-Channel": "PORTAL"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


token = sign_in(sys.argv[1], sys.argv[2])
f = get(token, "/v1/me/traffic-file")
v = get(token, "/v1/me/violations")["items"]
a = get(token, "/v1/me/black-point-account")
t = get(token, "/v1/me/transactions")["items"]
i = get(token, "/v1/me/inbox")["items"]
p = get(token, "/v1/me/payables")["items"]
print(f"file {f['fileNumber']} {f['status']} | violations {len(v)} ({sum(x['status'] == 'OPEN' for x in v)} open)"
      f" | points {a['activePoints']}/{a['threshold']} | transactions {len(t)} | inbox {len(i)} | payables {len(p)}")
ok = f["fileNumber"] and len(v) >= 3 and len(t) >= 1
print("DEMO CHECK OK" if ok else "DEMO CHECK FAIL")
sys.exit(0 if ok else 1)
