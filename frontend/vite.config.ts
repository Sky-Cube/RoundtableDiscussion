import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

export default defineConfig(async ({ command, mode, isPreview }) => {
  const fixtureMode = command === 'serve' && mode === 'fixture' && !isPreview
  const proxy = { '/api/v1': { target: 'http://127.0.0.1:8080', changeOrigin: true } }

  return {
    plugins: [
      vue(),
      ...(fixtureMode ? [(await import('./dev/fixture-api')).fixtureApi()] : []),
    ],
    server: { host: '127.0.0.1', port: 5173, proxy: fixtureMode ? undefined : proxy },
    preview: { host: '127.0.0.1', proxy },
    test: { environment: 'jsdom', include: ['tests/**/*.spec.ts'], exclude: ['tests/browser/**'] },
  }
})
