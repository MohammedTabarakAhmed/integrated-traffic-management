// Stand-in for the payment gateway's hosted pages (partner side, ICD-03), served by the Vite dev server for the demo.
// The WireMock gateway opens the session and redirects the customer here; "Pay" sends the signed SUCCESS callback to
// payment-service through the gateway, as the real partner would, then returns the customer to the portal.
// The callback key and the adapter account come from infra/local/.env and stay on this Node side, never in the browser.
// ITMS never sees card data: the card fields on this page are decoration and are not sent anywhere.
import { createHmac, randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resolve } from 'node:path'
import type { Plugin } from 'vite'

type Env = Record<string, string>
type Sent = { body: Record<string, unknown> }

function readEnv(): Env {
  try {
    const text = readFileSync(resolve(__dirname, '../../infra/local/.env'), 'utf8')
    return Object.fromEntries(text.split(/\r?\n/).filter((l) => /^[A-Z_][A-Z0-9_]*=/.test(l))
      .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1).replace(/^['"]|['"]$/g, '')]))
  } catch {
    return {}
  }
}

const esc = (s: string) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)

async function body(req: IncomingMessage): Promise<URLSearchParams> {
  let raw = ''
  for await (const chunk of req) raw += chunk
  return new URLSearchParams(raw)
}

export function mockGateway(opts: { gateway: string; keycloak: string }): Plugin {
  const sent = new Map<string, Sent>() // payment id -> the callback we sent, for the UAT-4 retry
  let token: { value: string; until: number } | null = null

  async function adapterToken(env: Env): Promise<string> {
    if (token && token.until > Date.now()) return token.value
    const res = await fetch(`${opts.keycloak}/realms/itms/protocol/openid-connect/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'password', client_id: 'itms-dev-cli', scope: 'openid',
        username: 'dev.maker', password: env.DEV_MAKER_PASSWORD ?? '' }),
    })
    if (!res.ok) throw new Error(`gateway adapter sign-in failed (${res.status})`)
    const t = (await res.json()) as { access_token: string; expires_in: number }
    token = { value: t.access_token, until: Date.now() + (t.expires_in - 20) * 1000 }
    return token.value
  }

  async function sendCallback(paymentId: string, cb: Record<string, unknown>) {
    const env = readEnv()
    const res = await fetch(`${opts.gateway}/v1/payments/${paymentId}/callbacks`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${await adapterToken(env)}`, 'Content-Type': 'application/json',
        'X-Channel': 'PARTNER', 'Idempotency-Key': randomUUID() },
      body: JSON.stringify(cb),
    })
    const json = (await res.json().catch(() => ({}))) as Record<string, unknown>
    if (!res.ok) throw new Error(`callback refused (${res.status}): ${json.detail ?? ''}`)
    return json as { receiptNumber?: string; duplicate: boolean; status: string }
  }

  async function confirm(req: IncomingMessage, res: ServerResponse, id: string) {
    const form = await body(req)
    const amount = form.get('amount') ?? '0'
    const currency = form.get('currency') ?? 'XTS'
    const paidAt = new Date().toISOString().replace(/\.\d+Z$/, 'Z')
    const txn = `GW-${Date.now().toString(36).toUpperCase()}`
    const key = readEnv().DEV_PAYMENT_CALLBACK_KEY ?? ''
    const signature = createHmac('sha256', key).update([id, txn, 'SUCCESS', amount, currency, paidAt].join('|')).digest('hex')
    const cb = { gatewayTxnId: txn, merchantRef: id, status: 'SUCCESS', amountMinor: Number(amount), currency, paidAt, signature }
    let back = form.get('return') || '/#/receipt'
    // No open redirect: only this portal, and a same-site path (not //host or /\host, which browsers treat as another site).
    const samePath = back.startsWith('/') && !back.startsWith('//') && !back.startsWith('/\\')
    if (!back.startsWith('http://localhost:5173/') && !samePath) back = '/#/receipt'
    try {
      const out = await sendCallback(id, cb)
      sent.set(id, { body: cb })
      const q = new URLSearchParams({ payment: id, receipt: out.receiptNumber ?? '', amount, currency, txn })
      res.writeHead(303, { Location: `${back}${back.includes('?') ? '&' : '?'}${q}` }).end()
    } catch (e) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' }).end(String((e as Error).message))
    }
  }

  async function replay(res: ServerResponse, id: string) {
    const s = sent.get(id)
    res.setHeader('Content-Type', 'application/json')
    if (!s) return res.writeHead(404).end(JSON.stringify({ detail: 'No callback was sent for this payment' }))
    try {
      res.end(JSON.stringify(await sendCallback(id, s.body)))
    } catch (e) {
      res.writeHead(502).end(JSON.stringify({ detail: (e as Error).message }))
    }
  }

  function page(res: ServerResponse, id: string, q: URLSearchParams) {
    const amount = Number(q.get('amount') ?? 0)
    const currency = q.get('currency') ?? 'XTS'
    const shown = `${currency === 'XTS' ? 'AED' : esc(currency)} ${(amount / 100).toLocaleString('en', { minimumFractionDigits: 2 })}`
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(`<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SecurePay checkout (mock)</title>
<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;800&display=swap" rel="stylesheet">
<style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;font-family:Inter,system-ui,sans-serif;
background:linear-gradient(135deg,#f5f7fb,#e6ebf5);color:#0f172a;padding:16px}
.box{width:100%;max-width:440px;background:#fff;border-radius:22px;box-shadow:0 30px 80px -30px rgba(15,23,42,.35);overflow:hidden}
.head{padding:22px 26px;background:#0f172a;color:#fff;display:flex;align-items:center;gap:12px}
.head b{font-size:18px}.lock{margin-left:auto;font-size:12px;opacity:.75}
.amt{padding:22px 26px;border-bottom:1px solid #eef1f6}.amt small{color:#64748b}.amt .big{font-size:32px;font-weight:800;margin-top:4px}
form{padding:22px 26px;display:grid;gap:12px}label{font-size:12px;color:#64748b;display:grid;gap:6px}
input{font:inherit;padding:12px 14px;border:1px solid #dbe2ee;border-radius:12px;color:#0f172a}
.row{display:grid;grid-template-columns:1fr 1fr;gap:12px}
button{font:inherit;font-weight:700;padding:15px;border:0;border-radius:14px;color:#fff;cursor:pointer;
background:linear-gradient(135deg,#0ea5e9,#6366f1);box-shadow:0 12px 30px -12px #6366f1}
button[disabled]{opacity:.6}.foot{padding:0 26px 22px;color:#94a3b8;font-size:11.5px;text-align:center}
.ref{font-family:ui-monospace,monospace;font-size:12px;color:#64748b;margin-top:6px}
</style></head><body><div class="box">
<div class="head"><svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#38bdf8" stroke-width="2"><rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/></svg><b>SecurePay</b><span class="lock">&#128274; mock gateway · test mode</span></div>
<div class="amt"><small>Pay ITMS · traffic fines</small><div class="big">${shown}</div><div class="ref">Merchant ref ${esc(id)}</div></div>
<form method="post" action="/mock-gateway/pay/${esc(id)}/confirm" onsubmit="this.querySelector('button').disabled=true;this.querySelector('button').textContent='Authorising…'">
<input type="hidden" name="amount" value="${amount}"><input type="hidden" name="currency" value="${esc(currency)}">
<input type="hidden" name="return" value="${esc(q.get('return') ?? '')}">
<label>Card number<input value="4111 1111 1111 1111" autocomplete="off" aria-label="Card number (test)"></label>
<div class="row"><label>Expiry<input value="12 / 29" autocomplete="off"></label><label>CVV<input value="•••" autocomplete="off"></label></div>
<label>Name on card<input value="DEMO CUSTOMER" autocomplete="off"></label>
<button type="submit">Pay ${shown}</button></form>
<div class="foot">Test page standing in for the gateway's hosted checkout. Card data stays with the gateway (PCI scope) — ITMS only receives the signed confirmation.</div>
</div></body></html>`)
  }

  return {
    name: 'itms-mock-gateway',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost')
        const m = url.pathname.match(/^\/mock-gateway\/pay\/([0-9a-f-]{36})(\/confirm|\/replay)?$/i)
        if (!m) return next()
        const [, id, action] = m
        if (!action && req.method === 'GET') return page(res, id, url.searchParams)
        if (action === '/confirm' && req.method === 'POST') return void confirm(req, res, id)
        if (action === '/replay' && req.method === 'POST') return void replay(res, id)
        res.writeHead(405).end()
      })
    },
  }
}
