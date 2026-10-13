import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { demoOps } from './demo-ops'
import { mockGateway } from './mock-gateway'

// The portal talks only to the gateway (/v1) and to Keycloak (/realms) through this dev proxy: same origin, no CORS.
// Keycloak sees Host localhost:8180, so token issuers stay what the services expect.
const gateway = process.env.ITMS_GATEWAY ?? 'http://localhost:9080'
const keycloak = process.env.ITMS_KEYCLOAK ?? 'http://localhost:8180'

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
      '/realms': {
        target: keycloak,
        changeOrigin: true,
        configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin')),
      },
    },
  },
})
