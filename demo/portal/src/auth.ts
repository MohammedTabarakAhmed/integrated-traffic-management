// Sign-in. Customers: authorisation code + PKCE with the public client itms-portal; Keycloak sends them straight to the
// national digital ID (mock). Staff (control room): the local dev client itms-dev-cli with a dev account. Tokens live in
// sessionStorage only, so closing the tab signs out.

const REALM = '/realms/itms/protocol/openid-connect'
const KEYCLOAK = 'http://localhost:8180' // the browser must see the real issuer for redirects
const PORTAL = 'itms-portal'
const DEV_CLI = 'itms-dev-cli'

export type Session = { accessToken: string; idToken?: string; refreshToken?: string; expiresAt: number; claims: Claims }
export type Claims = Record<string, unknown> & {
  sub: string
  preferred_username?: string
  name?: string
  national_id?: string
  subject_type?: string
  acr?: string
}
export type Kind = 'customer' | 'staff' | 'checker' // checker: the second person of maker-checker (module tour)

const storeKey = (k: Kind) => `itms.session.${k}`

function decode(jwt: string): Claims {
  const part = jwt.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
  const json = decodeURIComponent(
    atob(part).split('').map((c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
  )
  return JSON.parse(json)
}

function toSession(t: { access_token: string; id_token?: string; refresh_token?: string; expires_in: number }): Session {
  return {
    accessToken: t.access_token,
    idToken: t.id_token,
    refreshToken: t.refresh_token,
    expiresAt: Date.now() + t.expires_in * 1000,
    claims: decode(t.access_token),
  }
}

export function load(k: Kind): Session | null {
  try {
    const raw = sessionStorage.getItem(storeKey(k))
    if (!raw) return null
    const s = JSON.parse(raw) as Session
    // Still signed in while the access token is valid or a refresh token can renew it (see fresh()).
    return s.expiresAt > Date.now() + 5_000 || s.refreshToken ? s : null
  } catch {
    return null
  }
}

/**
 * The session with a valid access token: Keycloak issues 5-minute access tokens, so a token about to expire is renewed
 * with the refresh token (same client). Returns null, and forgets the session, when it can no longer be renewed.
 */
export async function fresh(k: Kind): Promise<Session | null> {
  const s = load(k)
  if (!s) return null
  if (s.expiresAt > Date.now() + 30_000) return s
  if (!s.refreshToken) { save(k, null); return null }
  try {
    const res = await fetch(`${REALM}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'refresh_token', client_id: k === 'customer' ? PORTAL : DEV_CLI, refresh_token: s.refreshToken }),
    })
    if (!res.ok) { save(k, null); return null }
    const t = await res.json()
    const next = toSession({ ...t, id_token: t.id_token ?? s.idToken, refresh_token: t.refresh_token ?? s.refreshToken })
    save(k, next)
    return next
  } catch {
    return s.expiresAt > Date.now() ? s : null
  }
}

function save(k: Kind, s: Session | null) {
  try {
    if (s) sessionStorage.setItem(storeKey(k), JSON.stringify(s))
    else sessionStorage.removeItem(storeKey(k))
  } catch {
    /* storage unavailable: the session lasts for this page only */
  }
}

const b64url = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

const redirectUri = () => `${location.origin}/`

/** Starts the customer sign-in. acr 'otp' asks the national ID for the one-time password (step-up, SEC-002). */
export async function signInCustomer(opts: { acr?: 'otp'; returnTo?: string; lang?: string } = {}) {
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)))
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)))
  const state = b64url(crypto.getRandomValues(new Uint8Array(16)))
  sessionStorage.setItem('itms.pkce', JSON.stringify({ verifier, state, returnTo: opts.returnTo ?? location.hash }))
  const q = new URLSearchParams({
    client_id: PORTAL,
    response_type: 'code',
    scope: 'openid',
    redirect_uri: redirectUri(),
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  })
  if (opts.acr) q.set('acr_values', opts.acr)
  if (opts.lang) q.set('ui_locales', opts.lang)
  location.assign(`${KEYCLOAK}${REALM}/auth?${q}`)
}

/** Finishes the redirect if the URL carries a code; returns the hash to go back to. */
export async function completeRedirect(): Promise<string | null> {
  const p = new URLSearchParams(location.search)
  const code = p.get('code')
  if (!code) return null
  const pending = JSON.parse(sessionStorage.getItem('itms.pkce') ?? 'null')
  sessionStorage.removeItem('itms.pkce')
  history.replaceState(null, '', location.pathname + (pending?.returnTo ?? ''))
  if (!pending || pending.state !== p.get('state')) throw new Error('Sign-in state did not match; please try again.')
  const res = await fetch(`${REALM}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: PORTAL,
      code,
      redirect_uri: redirectUri(),
      code_verifier: pending.verifier,
    }),
  })
  if (!res.ok) throw new Error(`Sign-in failed (${res.status})`)
  save('customer', toSession(await res.json()))
  return pending.returnTo ?? ''
}

/** Staff sign-in for the control room (local dev accounts only). */
export async function signInStaff(username: string, password: string, kind: Kind = 'staff'): Promise<Session> {
  const res = await fetch(`${REALM}/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'password', client_id: DEV_CLI, scope: 'openid', username, password }),
  })
  if (!res.ok) throw new Error(res.status === 401 ? 'Wrong user name or password' : `Sign-in failed (${res.status})`)
  const s = toSession(await res.json())
  save(kind, s)
  return s
}

export function signOut(k: Kind) {
  const s = load(k)
  save(k, null)
  if (k === 'customer' && s?.idToken) {
    const q = new URLSearchParams({ id_token_hint: s.idToken, post_logout_redirect_uri: redirectUri() })
    location.assign(`${KEYCLOAK}${REALM}/logout?${q}`)
  }
}
