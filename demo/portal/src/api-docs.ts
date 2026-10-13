// API explorer (demo D3.3): Swagger UI over the OpenAPI 3.1 spec of every service, served from the repository by the
// dev server. "Try it out" calls go through the same gateway proxy as the portal, with the signed-in staff token, the
// channel header and a fresh Idempotency-Key on changing calls, so the platform's own rules apply to every call.
import { SwaggerUIBundle, SwaggerUIStandalonePreset } from 'swagger-ui-dist'
import 'swagger-ui-dist/swagger-ui.css'
import './api-docs.css'
import { fresh } from './auth'

// Access tokens last 5 minutes; keep the signed-in staff token renewed while the explorer is open.
void fresh('staff')
setInterval(() => { void fresh('staff') }, 60_000)

const SERVICES = ['traffic-file', 'violation', 'payment', 'vehicle', 'licensing', 'examination', 'accident', 'permit', 'rental',
  'road-permit', 'marine', 'crossborder', 'search', 'reporting', 'iam', 'reference-data', 'transaction', 'hold-eligibility',
  'fees', 'rules', 'workflow', 'notification', 'document', 'appointment', 'audit', 'device', 'geo']

function token(): { value: string; who: string } | null {
  for (const kind of ['staff', 'checker', 'customer']) {
    try {
      const s = JSON.parse(sessionStorage.getItem(`itms.session.${kind}`) ?? 'null')
      if (s && s.expiresAt > Date.now()) return { value: s.accessToken, who: s.claims?.preferred_username ?? kind }
    } catch { /* ignore */ }
  }
  return null
}

const t = token()
document.body.insertAdjacentHTML('afterbegin', `
  <header class="itms-bar">
    <a href="/#/platform" class="itms-brand"><span class="itms-mark">IT</span><span><b>ITMS API Explorer</b><small>${SERVICES.length} services · OpenAPI 3.1 · through the APISIX gateway</small></span></a>
    <span class="itms-who ${t ? 'ok' : ''}">${t ? `Signed in as <b>${t.who}</b>: calls carry this token` : 'Not signed in: open the control room or modules page first'}</span>
  </header>
  <div id="swagger"></div>`)

SwaggerUIBundle({
  dom_id: '#swagger',
  urls: SERVICES.map((s) => ({ url: `/specs/${s}.yaml`, name: `${s}-service` })),
  'urls.primaryName': 'violation-service',
  presets: [SwaggerUIBundle.presets.apis, SwaggerUIStandalonePreset],
  layout: 'StandaloneLayout',
  deepLinking: true,
  tryItOutEnabled: true,
  displayRequestDuration: true,
  filter: true,
  requestInterceptor: (request) => {
    const req = request as unknown as { url: string; method: string; headers: Record<string, string> }
    const tk = token()
    if (tk) req.headers.Authorization = `Bearer ${tk.value}`
    req.headers['X-Channel'] = req.headers['X-Channel'] ?? 'BACKOFFICE'
    if (req.method !== 'GET' && !req.headers['Idempotency-Key']) req.headers['Idempotency-Key'] = crypto.randomUUID()
    return request
  },
})
