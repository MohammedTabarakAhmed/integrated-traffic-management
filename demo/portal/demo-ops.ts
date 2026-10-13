// Operations view for the demo (Vite dev server only): probes every service port of the local stack and answers
// /demo/health with up/down and latency, so the architecture map shows all services, including those Prometheus does
// not scrape. Any HTTP answer (401 included) means the service is up; it only reads.
import { readFileSync } from 'node:fs'
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
      server.middlewares.use('/demo/health', async (_req, res) => {
        const services = await Promise.all(SERVICES.map(async ([name, port, group]) => ({ name, port, group, ...(await probe(`http://127.0.0.1:${port}/v1/`)) })))
        const infra = await Promise.all(INFRA.map(async ([name, url]) => ({ name, ...(await probe(url)) })))
        res.setHeader('Content-Type', 'application/json')
        res.end(JSON.stringify({ at: new Date().toISOString(), services, infra }))
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
