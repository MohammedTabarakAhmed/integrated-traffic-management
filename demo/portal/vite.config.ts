import type { IncomingMessage, ServerResponse } from 'node:http'
import { defineConfig, type ProxyOptions } from 'vite'
import react from '@vitejs/plugin-react'
import { demoOps } from './demo-ops'
import { mockGateway } from './mock-gateway'

// The portal talks only to the gateway (/v1) and to Keycloak (/realms) through this dev proxy: same origin, no CORS.
// Keycloak sees Host localhost:8180, so token issuers stay what the services expect.
const gateway = process.env.ITMS_GATEWAY ?? 'http://localhost:9080'
const keycloak = process.env.ITMS_KEYCLOAK ?? 'http://localhost:8180'

// The address the browser used: a tunnel (ngrok) sends its own Host and X-Forwarded-Proto https.
const publicOrigin = (req: IncomingMessage) => {
  const first = (v: string | string[] | undefined) => String(Array.isArray(v) ? v[0] : v ?? '').split(',')[0].trim()
  return `${first(req.headers['x-forwarded-proto']) || 'http'}://${first(req.headers['x-forwarded-host']) || first(req.headers.host)}`
}

// Keycloak through the proxy. Its pages and redirects name its own address (KC_HOSTNAME http://localhost:8180),
// which a remote tester cannot reach; this swaps that address for the one the browser used, in Location headers and
// in HTML, CSS and scripts. Token responses (JSON) pass untouched, so issuers stay localhost:8180.
const keycloakProxy: ProxyOptions = {
  target: keycloak,
  changeOrigin: true,
  selfHandleResponse: true,
  configure: (proxy) => {
    proxy.on('proxyReq', (req) => { req.removeHeader('origin'); req.setHeader('accept-encoding', 'identity') })
    proxy.on('proxyRes', (up, req: IncomingMessage, res: ServerResponse) => {
      const origin = publicOrigin(req)
      const swap = (s: string) => s.split(keycloak).join(origin).split(keycloak.replace(/\//g, '\\/')).join(origin.replace(/\//g, '\\/'))
      const headers = { ...up.headers }
      if (typeof headers.location === 'string') headers.location = swap(headers.location)
      if (!/text\/html|text\/css|javascript/.test(String(up.headers['content-type'] ?? ''))) {
        res.writeHead(up.statusCode ?? 502, headers)
        up.pipe(res)
        return
      }
      const chunks: Buffer[] = []
      up.on('data', (c: Buffer) => chunks.push(c))
      up.on('end', () => {
        const body = Buffer.from(swap(Buffer.concat(chunks).toString('utf8')), 'utf8')
        headers['content-length'] = String(body.length)
        res.writeHead(up.statusCode ?? 502, headers)
        res.end(body)
      })
    })
  },
}

export default defineConfig({
  plugins: [react(), mockGateway({ gateway, keycloak }), demoOps()],
  build: { rollupOptions: { input: { main: 'index.html', api: 'api.html' } } },
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/v1': { target: gateway, changeOrigin: true },
      // Read-only metrics for the architecture map.
      '/prom': { target: process.env.ITMS_PROMETHEUS ?? 'http://localhost:9090', changeOrigin: true, rewrite: (p) => p.replace(/^\/prom/, '') },
      '/realms': keycloakProxy,
      '/resources': keycloakProxy, // the sign-in pages' styles and scripts
    },
  },
})
