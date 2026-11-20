import { defineConfig } from '@playwright/test'

const port = 5183
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: 'tests/browser',
  use: {
    baseURL, channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chromium', trace: 'on',
    launchOptions: { args: ['--enable-unsafe-swiftshader'] },
  },
  projects: [
    { name: '1920x1080', use: { viewport: { width: 1920, height: 1080 } } },
    { name: '1366x768', use: { viewport: { width: 1366, height: 768 } } },
    { name: '390x844', use: { viewport: { width: 390, height: 844 } } },
  ],
  webServer: {
    command: `npm run dev:fixture -- --port ${port} --strictPort`,
    url: baseURL,
    reuseExistingServer: false,
  },
})
