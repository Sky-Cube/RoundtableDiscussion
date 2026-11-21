/**
 * P11 交付复核：验证 SQLite 初始化脚本的幂等性。
 *
 * 在全新空白库上连续启动两次后端，确认预设始终为 6 条且内容一致，
 * 不会因为重复执行 init.sql 产生重复种子数据。
 *
 * 用法：node tests/system/verify-init-idempotent.mjs <runId>
 */
import { spawn } from 'node:child_process'
import { access, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { setTimeout as sleep } from 'node:timers/promises'

const runId = process.argv[2] ?? 'local'
const repoRoot = resolve(import.meta.dirname, '../../..')
const outputDir = join(repoRoot, 'test-results', 'prompt', 'P11', runId)
const tempDir = join(outputDir, 'sqlite-init-' + randomUUID())
const database = join(tempDir, 'roundtable.db')
const port = 18085
const jar = join(repoRoot, 'backend', 'target', 'ai-panel-backend-exec.jar')

async function startBackend() {
  const child = spawn('java', [
    '-jar', jar,
    '--spring.profiles.active=dev',
    '--server.port=' + port,
    '--panel.db.path=' + database,
    '--panel.db.allowed-root=' + tempDir,
    '--panel.db.init-script=classpath:db/init.sql',
    '--panel.db.initialize=true',
    '--panel.gateway.mode=scripted',
    '--logging.file.name=' + join(outputDir, 'init-backend.log'),
  ], { cwd: repoRoot, stdio: 'ignore', windowsHide: true })

  const deadline = Date.now() + 150_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error('后端启动失败，退出码 ' + child.exitCode)
    try {
      const response = await fetch('http://127.0.0.1:' + port + '/api/v1/presets')
      if (response.ok) return child
    } catch {
      // 等待监听。
    }
    await sleep(150)
  }
  throw new Error('后端启动超时')
}

async function fetchPresets() {
  const response = await fetch('http://127.0.0.1:' + port + '/api/v1/presets')
  const body = await response.json()
  return body.data
}

async function stop(child) {
  if (child.exitCode === null) {
    child.kill()
    await sleep(800)
  }
}

/** 空白库断言：启动前目录内不应存在旧数据库文件。 */
async function assertBlank() {
  const exists = await access(database).then(() => true).catch(() => false)
  if (exists) throw new Error('临时库不是空白库')
}

await mkdir(tempDir, { recursive: true })
await assertBlank()

const first = await startBackend()
const firstPresets = await fetchPresets()
await stop(first)

// 同一库再次启动：初始化脚本必须幂等。
const second = await startBackend()
const secondPresets = await fetchPresets()
await stop(second)

const fingerprint = presets => presets.map(preset =>
  preset.slug + '|' + preset.topic + '|' + preset.expertCount + '|' + preset.participants.map(member => member.id).join(','),
)

const result = {
  runId,
  jar,
  blankDatabase: true,
  firstCount: firstPresets.length,
  secondCount: secondPresets.length,
  identical: JSON.stringify(fingerprint(firstPresets)) === JSON.stringify(fingerprint(secondPresets)),
  slugs: firstPresets.map(preset => preset.slug),
  passed: firstPresets.length === 6
    && secondPresets.length === 6
    && JSON.stringify(fingerprint(firstPresets)) === JSON.stringify(fingerprint(secondPresets)),
}

await writeFile(join(outputDir, 'init-idempotent.json'), JSON.stringify(result, null, 2))
console.log(JSON.stringify(result, null, 2))

// 清理本次临时库与 WAL/SHM 文件，保留结果清单。
await rm(tempDir, { recursive: true, force: true })
process.exitCode = result.passed ? 0 : 1
