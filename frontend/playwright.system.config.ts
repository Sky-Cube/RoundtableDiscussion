/**
 * E2E 系统级测试配置。
 *
 * 与默认的 `playwright.config.ts`（前端夹具模式）分离：
 * 通过 webServer 托管真实 Spring 后端、临时 SQLite、模型替身与 Vite 前端，
 * 服务生命周期覆盖全部用例，避免测试进程中子进程被提前回收。
 */
import { defineConfig } from '@playwright/test'
import { join } from 'node:path'

const frontendDir = process.cwd()
const repoRoot = join(frontendDir, '..')
const runId = process.env.SYSTEM_RUN_ID ?? 'local'
const outputDir = join(repoRoot, 'test-results', 'prompt', 'P10', runId)
const artifactDir = join(outputDir, 'artifacts')
const databaseDir = process.env.SYSTEM_DB_DIR

if (!databaseDir) {
  throw new Error('缺少 SYSTEM_DB_DIR，请使用 npm run test:system 启动系统级测试')
}

const jar = join(repoRoot, 'backend', 'target', 'ai-panel-backend-exec.jar')
const log = (name: string) => join(outputDir, name)

export default defineConfig({
  testDir: './tests/system',
  testMatch: '**/*.spec.ts',
  outputDir: artifactDir,
  fullyParallel: false,
  workers: 1,
  timeout: 240_000,
  expect: { timeout: 20_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: join(outputDir, 'playwright.json') }],
    ['html', { outputFolder: join(outputDir, 'html'), open: 'never' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:5299',
    channel: 'chrome',
    headless: true,
    viewport: { width: 1366, height: 768 },
    // 无头环境只有 SwiftShader 软件 WebGL，多个演播厅并存时会长时间占满主线程，
    // 导致 SPA 导航与点击排队。系统级用例验证的是布局、滚动、键盘与事件驱动行为，
    // 因此关闭 WebGL 走组件自带的 flat 渲染回退，避免软件光栅化拖垮断言时序。
    reducedMotion: 'reduce',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    launchOptions: {
      args: ['--disable-webgl', '--disable-webgl2', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    },
  },
  webServer: [
    {
      command: `"${process.execPath}" "${join(frontendDir, 'tests', 'system', 'support', 'fake-upstream.mjs')}" 1> "${log('upstream.log')}" 2>&1`,
      url: 'http://127.0.0.1:18082/control',
      env: { FAKE_UPSTREAM_PORT: '18082' },
      reuseExistingServer: false,
      timeout: 30_000,
    },
    {
      command: [
        'java', '-jar', `"${jar}"`,
        '--server.port=18081',
        `--panel.db.path="${join(databaseDir, 'roundtable.db')}"`,
        `--panel.db.allowed-root="${databaseDir}"`,
        '--panel.db.init-script=classpath:db/init.sql',
        '--panel.db.initialize=true',
        '--panel.gateway.mode=real',
        '--panel.gateway.evidence-enabled=false',
        '--panel.orchestration.stage-delay-millis=1200',
        '--panel.request.allowed-origin-ports=5299',
        `--logging.file.name="${log('backend-app.log')}"`,
        `1> "${log('backend.log')}" 2>&1`,
      ].join(' '),
      url: 'http://127.0.0.1:18081/api/v1/presets',
      env: {
        PANEL_AI_BASE_URL: 'http://127.0.0.1:18082/v1',
        PANEL_AI_API_KEY: 'system-e2e-synthetic-key',
        PANEL_AI_MODEL: 'synthetic-roundtable',
      },
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: `"${process.execPath}" "${join(frontendDir, 'node_modules', 'vite', 'bin', 'vite.js')}" --port 5299 --strictPort 1> "${log('frontend.log')}" 2>&1`,
      url: 'http://127.0.0.1:5299/',
      env: { PANEL_BACKEND_URL: 'http://127.0.0.1:18081' },
      reuseExistingServer: false,
      timeout: 120_000,
    },
  ],
})
