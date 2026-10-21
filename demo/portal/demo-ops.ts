// Operations view for the demo (Vite dev server only): probes every service port of the local stack and answers
// /demo/health with up/down and latency, so the architecture map shows all services, including those Prometheus does
// not scrape. Any HTTP answer (401 included) means the service is up; it only reads.
import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import type { Plugin } from 'vite'

export const SERVICES: [string, number, string][] = [
  ['reference-data', 8081, 'shared'], ['iam', 8082, 'shared'], ['transaction', 8083, 'shared'], ['hold-eligibility', 8084, 'shared'],
  ['traffic-file', 8085, 'shared'], ['fees', 8086, 'shared'], ['rules', 8087, 'shared'], ['workflow', 8088, 'shared'],
  ['notification', 8089, 'shared'], ['document', 8091, 'shared'], ['appointment', 8092, 'shared'], ['audit', 8093, 'shared'],
  ['payment', 8094, 'revenue'], ['vehicle', 8095, 'vehicles'], ['violation', 8096, 'violations'], ['device', 8097, 'violations'],
  ['licensing', 8098, 'licensing'], ['examination', 8099, 'licensing'], ['accident', 8100, 'accidents'], ['rental', 8101, 'permits'],
  ['permit', 8102, 'permits'], ['geo', 8103, 'road'], ['road-permit', 8104, 'road'], ['marine', 8105, 'road'],
  ['crossborder', 8106, 'crossborder'], ['search', 8107, 'channels'], ['reporting', 8108, 'analytics'],
]
// Portal page file -> route, for the requirements explorer's "Try it" links.
const ROUTE_OF: Record<string, string> = {
  Home: 'home', Dashboard: 'dashboard', Receipt: 'dashboard', Objection: 'dashboard', Profile: 'profile', Documents: 'documents',
  Services: 'services', Settings: 'settings', Assistant: 'assistant', ControlRoom: 'control', BackOffice: 'backoffice',
  NewFile: 'backoffice?tab=newfile', Applications: 'backoffice?tab=applications', Modules: 'modules', Analytics: 'analytics',
  Reports: 'reports', Admin: 'admin', Platform: 'platform', Qa: 'qa', Guide: 'guide',
}
const INFRA: [string, string][] = [['gateway', 'http://127.0.0.1:9080/'], ['keycloak', 'http://127.0.0.1:8180/realms/itms'], ['prometheus', 'http://127.0.0.1:9090/-/ready']]

async function probe(url: string) {
  const t = performance.now()
  try {
    await fetch(url, { signal: AbortSignal.timeout(2500) })
    return { up: true, ms: Math.round(performance.now() - t) }
  } catch {
    return { up: false, ms: null }
  }
}

export function demoOps(): Plugin {
  return {
    name: 'itms-demo-ops',
    configureServer(server) {
      // Tunnel gate helper: ngrok's traffic policy redirects ?key=... here; this sets the day cookie the policy accepts.
      // The key is DEMO_TUNNEL_PASSWORD from infra/local/.env (written by tunnel.sh once).
      let gateKey = ''
      try {
        const envText = readFileSync(resolve(__dirname, '../../infra/local/.env'), 'utf8')
        gateKey = envText.split(/\r?\n/).find((l) => l.startsWith('DEMO_TUNNEL_PASSWORD='))?.split('=')[1]?.trim() ?? ''
      } catch { /* no env: the gate helper stays off */ }
      server.middlewares.use('/gate-set', (req, res) => {
        if (!gateKey) { res.statusCode = 404; res.end(); return }
        // middleware mount strips the path: req.url is "/?key=..." here.
        const q = new URLSearchParams((req.url ?? '').replace(/^\/*[^?]*\?/, '').replace(/^\?/, ''))
        if (q.get('key') !== gateKey) { res.statusCode = 401; res.end('bad key'); return }
        res.setHeader('Set-Cookie', `itms_gate=${gateKey}; Path=/; Max-Age=86400; SameSite=Lax`)
        res.statusCode = 302
        res.setHeader('Location', '/')
        res.end()
      })
      server.middlewares.use('/demo/health', async (_req, res) => {
        const services = await Promise.all(SERVICES.map(async ([name, port, group]) => ({ name, port, group, ...(await probe(`http://127.0.0.1:${port}/v1/`)) })))
        const infra = await Promise.all(INFRA.map(async ([name, url]) => ({ name, ...(await probe(url)) })))
        res.setHeader('Content-Type', 'application/json')
        res.end(JSON.stringify({ at: new Date().toISOString(), services, infra }))
      })
      // Requirements explorer: the BRD ledger (docs/brief/01, the authoritative status) plus, for each requirement, the
      // portal screens whose source names it, so "Try it" links come from the code, not from a hand-made list.
      server.middlewares.use('/demo/ledger', (_req, res) => {
        try {
          const text = readFileSync(resolve(__dirname, '../../docs/brief/01_PROJECT_REQUIREMENTS.md'), 'utf8')
          const shownIn: Record<string, Set<string>> = {}
          const pages = resolve(__dirname, 'src/pages')
          for (const f of readdirSync(pages).filter((n) => n.endsWith('.tsx'))) {
            const route = ROUTE_OF[f.replace('.tsx', '')]
            if (!route) continue
            for (const id of readFileSync(resolve(pages, f), 'utf8').match(/\b[A-Z]{2,3}-\d{3}\b/g) ?? []) (shownIn[id] ??= new Set()).add(route)
          }
          const rows = text.split(/\r?\n/).filter((l) => l.startsWith('| [')).map((l) => {
            const c = l.split('|').map((x) => x.trim())
            const id = c[2].replace(/`/g, '')
            return { status: c[1], id, priority: c[3], text: c[4], implementation: c[5], test: c[6], notes: c.slice(7).join(' | ').replace(/\|\s*$/, '').trim(),
              shownIn: [...(shownIn[id] ?? [])] }
          })
          res.setHeader('Content-Type', 'application/json')
          res.end(JSON.stringify({ rows }))
        } catch (e) {
          res.statusCode = 500
          res.end(JSON.stringify({ detail: (e as Error).message }))
        }
      })
      // OpenAPI specs for the API explorer (/api.html): read from the repository, only the services listed above.
      server.middlewares.use('/specs/', (req, res, next) => {
        const name = (req.url ?? '').replace(/^\//, '').replace(/\.yaml$/, '')
        if (!SERVICES.some(([s]) => s === name)) return next()
        try {
          const file = resolve(__dirname, `../../services/${name}-service/src/main/resources/openapi/${name}-service.v1.yaml`)
          res.setHeader('Content-Type', 'application/yaml; charset=utf-8')
          res.end(readFileSync(file, 'utf8'))
        } catch {
          res.statusCode = 404
          res.end()
        }
      })
    },
  }
}
