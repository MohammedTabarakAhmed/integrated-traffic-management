// The only way the app reaches ITMS: the gateway, with the caller's token. Changing calls carry an Idempotency-Key;
// errors are RFC 9457 problem+json and come back as ApiError.
import { fresh, type Kind } from './auth'

export class ApiError extends Error {
  status: number
  problem: { title?: string; detail?: string; code?: string; type?: string; [k: string]: unknown }
  constructor(status: number, problem: ApiError['problem']) {
    super(problem.detail || problem.title || `HTTP ${status}`)
    this.status = status
    this.problem = problem
  }
  get stepUpNeeded() {
    return this.status === 401 && JSON.stringify(this.problem).includes('insufficient_user_authentication')
  }
}

export class SignedOut extends Error {}

// The last API calls (method, path, status, time, correlation id; never tokens or bodies) for the hidden developer
// panel: click the logo five times.
export type CallLog = { at: number; method: string; path: string; status: number; ms: number; corr?: string }
export const recentCalls: CallLog[] = []
function logCall(c: CallLog) {
  recentCalls.unshift(c)
  recentCalls.length = Math.min(recentCalls.length, 25)
  dispatchEvent(new Event('itms-call'))
}

const channel: Record<Kind, string> = { customer: 'PORTAL', staff: 'BACKOFFICE', checker: 'BACKOFFICE' }

export async function call<T>(
  kind: Kind,
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  lang = 'en',
  channelOverride?: string,
): Promise<T> {
  const s = await fresh(kind)
  if (!s) throw new SignedOut()
  const headers: Record<string, string> = {
    Authorization: `Bearer ${s.accessToken}`,
    'X-Channel': channelOverride ?? channel[kind],
    'Accept-Language': lang,
    Accept: 'application/json, application/problem+json',
  }
  if (method !== 'GET') {
    headers['Idempotency-Key'] = crypto.randomUUID()
    headers['Content-Type'] = 'application/json'
  }
  const t0 = performance.now()
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  logCall({ at: Date.now(), method, path: path.split('?')[0], status: res.status, ms: Math.round(performance.now() - t0), corr: res.headers.get('x-correlation-id') ?? undefined })
  const text = await res.text()
  let json: unknown = undefined
  try {
    json = text ? JSON.parse(text) : undefined
  } catch {
    json = { detail: text.slice(0, 200) }
  }
  if (!res.ok) {
    // A 401 from the gateway may only carry the reason in WWW-Authenticate.
    const p = (json as ApiError['problem']) ?? {}
    const www = res.headers.get('www-authenticate')
    if (www) p.wwwAuthenticate = www
    throw new ApiError(res.status, p)
  }
  return json as T
}

// The local stack bills in XTS, the ISO 4217 test code (the real currency is Authority configuration); shown as AED.
export const money = (minor: number, currency = 'AED', lang = 'en') =>
  new Intl.NumberFormat(lang === 'ar' ? 'ar-AE' : 'en-AE', { style: 'currency', currency: currency === 'XTS' ? 'AED' : currency })
    .format(minor / 100)

export const when = (iso: string, lang = 'en') =>
  new Intl.DateTimeFormat(lang === 'ar' ? 'ar-AE' : 'en-GB', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  )
