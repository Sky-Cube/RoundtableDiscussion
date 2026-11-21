/**
 * E2E 系统级测试运行器。
 *
 * 负责本轮运行的隔离准备与收尾：
 * 1. 生成本轮证据目录与空白 SQLite 目录；
 * 2. 从子进程环境剔除服务端模型凭据，避免泄漏到前端；
 * 3. 调用 Playwright，服务由 webServer 托管；
 * 4. 测试结束后删除临时 SQLite、WAL 与 SHM 文件。
 *
 * 用法：npm run test:system（可选 SYSTEM_RUN_ID 指定证据目录名）
 */
import { spawn } from 'node:child_process'
import { mkdir, rm, stat, readdir } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'

const frontendDir = resolve(import.meta.dirname, '../..')
const repoRoot = resolve(frontendDir, '..')
const runId = process.env.SYSTEM_RUN_ID ?? 'system-' + Date.now()
const outputDir = join(repoRoot, 'test-results', 'prompt', 'P10', runId)
const databaseDir = join(outputDir, 'sqlite-' + randomUUID())

// 系统级用例直接启动可执行 jar；jar 比源码旧时验证的是过期产物，必须立即阻断。
async function newestMtime(directory) {
  let newest = 0
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) newest = Math.max(newest, await newestMtime(path))
    else newest = Math.max(newest, (await stat(path)).mtimeMs)
  }
  return newest
}

const jarPath = join(repoRoot, 'backend', 'target', 'ai-panel-backend-exec.jar')
const jarStat = await stat(jarPath).catch(() => null)
if (!jarStat) {
  throw new Error('缺少后端可执行 jar，请先执行 mvn -B package -DskipTests')
}
const newestSource = await newestMtime(join(repoRoot, 'backend', 'src', 'main'))
if (jarStat.mtimeMs < newestSource) {
  throw new Error('后端 jar 早于源码修改，请先执行 mvn -B package -DskipTests 再运行 E2E')
}

await mkdir(databaseDir, { recursive: true })

const env = { ...process.env, SYSTEM_RUN_ID: runId, SYSTEM_DB_DIR: databaseDir }
for (const key of Object.keys(env)) {
  if (key.startsWith('PANEL_AI_')) delete env[key]
}

const cli = join(frontendDir, 'node_modules', '@playwright', 'test', 'cli.js')
const child = spawn(process.execPath, [cli, 'test', '--config', 'playwright.system.config.ts'], {
  cwd: frontendDir,
  env,
  stdio: 'inherit',
  windowsHide: true,
})

const exitCode = await new Promise(resolvePromise => child.on('exit', code => resolvePromise(code ?? 1)))

// 本轮临时库不做保留，证据只保留日志、报告与截图。
await rm(databaseDir, { recursive: true, force: true })

console.log('E2E 证据目录：' + outputDir)
process.exitCode = exitCode
