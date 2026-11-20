import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig(async ({ command, mode, isPreview }) => {
  const fixtureMode = command === 'serve' && mode === 'test-fixture' && !isPreview
  const backendUrl = process.env.PANEL_BACKEND_URL?.trim() || 'http://127.0.0.1:8080'
  const proxy = { '/api/v1': { target: backendUrl, changeOrigin: true } }

  return {
    plugins: [
      vue(),
      ...(fixtureMode ? [(await import('./tests/fixtures/fixture-api')).fixtureApi()] : []),
    ],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy: fixtureMode ? undefined : proxy },
    preview: { host: '127.0.0.1', proxy },
    test: { environment: 'jsdom', include: ['tests/**/*.spec.ts'], exclude: ['tests/browser/**'] },
  }
})
