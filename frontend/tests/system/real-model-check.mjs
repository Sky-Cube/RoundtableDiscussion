/**
 * 真实模型质量与耗时检查（不参与自动化断言）
 *
 * 用途：用后端真实模型网关跑一场完整讨论，记录阶段耗时、发言质量与结论可追溯性。
 * 前提：当前进程环境已注入 PANEL_AI_BASE_URL / PANEL_AI_API_KEY / PANEL_AI_MODEL。
 * 产物：test-results/prompt/P10/<runId>-real/real-model-report.json 与 backend.log。
 */
import { spawn } from 'node:child_process'
import { createWriteStream } from 'node:fs'
import { mkdir, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { randomUUID } from 'node:crypto'
import { setTimeout as delay } from 'node:timers/promises'
import { createConnection } from 'node:net'

const frontendDir = resolve(import.meta.dirname, '../..')
const repoRoot = resolve(frontendDir, '..')
const runId = process.env.SYSTEM_RUN_ID ?? 'real-' + Date.now()
const outputDir = join(repoRoot, 'test-results', 'prompt', 'P10', runId + '-real')
const databaseDir = join(outputDir, 'sqlite-' + randomUUID())
const port = 18083
const baseUrl = 'http://127.0.0.1:' + port

const required = ['PANEL_AI_BASE_URL', 'PANEL_AI_API_KEY', 'PANEL_AI_MODEL']
const missing = required.filter(name => !process.env[name])
if (missing.length > 0) {
  console.error('缺少真实模型环境变量：' + missing.join(', '))
  process.exit(2)
}

await mkdir(databaseDir, { recursive: true })
const log = createWriteStream(join(outputDir, 'backend.log'))

const backend = spawn('java', [
  '-jar', join(repoRoot, 'backend', 'target', 'ai-panel-backend-exec.jar'),
  '--server.port=' + port,
  '--panel.db.path=' + join(databaseDir, 'roundtable.db'),
  '--panel.db.allowed-root=' + databaseDir,
  '--panel.gateway.mode=real',
  '--panel.gateway.evidence-enabled=false',
  '--panel.request.allowed-origin-ports=5299',
], { cwd: repoRoot, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] })
backend.stdout.pipe(log)
backend.stderr.pipe(log)

function stopBackend() {
  if (!backend.killed) {
    try {
      backend.kill()
    } catch {
      // 进程可能已退出，忽略即可。
    }
  }
}

async function reachable() {
  return new Promise(resolveReachable => {
    const socket = createConnection({ port, host: '127.0.0.1' })
    socket.once('connect', () => {
      socket.destroy()
      resolveReachable(true)
    })
    socket.once('error', () => resolveReachable(false))
  })
}

async function api(path, method = 'GET', body) {
  const response = await fetch(baseUrl + path, {
    method,
    headers: { Accept: 'application/json', ...(body && { 'Content-Type': 'application/json' }) },
    body: body && JSON.stringify(body),
  })
  const envelope = await response.json().catch(() => null)
  return { status: response.status, body: envelope }
}

function elapsed(from) {
  return Math.round(Number(process.hrtime.bigint() - from) / 1e6)
}

const report = { runId, topic: '真实模型验收：团队是否应在下季度把代码评审交给 AI 预审？', startedAt: new Date().toISOString() }
try {
  const bootStart = process.hrtime.bigint()
  let ready = false
  for (let attempt = 0; attempt < 120 && !ready; attempt += 1) {
    ready = await reachable()
    if (!ready) await delay(1000)
  }
  if (!ready) throw new Error('后端在 120 秒内未就绪')
  report.bootMillis = elapsed(bootStart)

  const rosterStart = process.hrtime.bigint()
  const created = await api('/api/v1/discussions', 'POST', { topic: report.topic, expertCount: 3 })
  report.rosterStatus = created.status
  report.rosterMillis = elapsed(rosterStart)
  if (created.status !== 201) throw new Error('创建讨论失败：' + JSON.stringify(created.body))
  const id = created.body.data.id
  report.discussionId = id
  report.roster = created.body.data.participants.map(member => ({
    role: member.role, displayName: member.displayName, title: member.title, perspective: member.perspective,
  }))

  const startAt = process.hrtime.bigint()
  const started = await api('/api/v1/discussions/' + id + '/start', 'POST')
  report.startStatus = started.status
  if (started.status !== 202) throw new Error('启动讨论失败：' + JSON.stringify(started.body))

  let snapshot = null
  let firstExpertMillis = null
  for (let attempt = 0; attempt < 180; attempt += 1) {
    await delay(2000)
    const current = await api('/api/v1/discussions/' + id)
    if (current.status !== 200) continue
    snapshot = current.body.data
    if (firstExpertMillis === null && snapshot.transcript.some(line => line.kind === 'EXPERT')) {
      firstExpertMillis = elapsed(startAt)
    }
    if (snapshot.status === 'COMPLETED' || snapshot.status === 'FAILED') break
  }
  report.firstExpertMillis = firstExpertMillis
  report.totalMillis = elapsed(startAt)
  if (!snapshot || !['COMPLETED', 'FAILED'].includes(snapshot.status)) {
    throw new Error('讨论在 360 秒内未进入终态，最后状态：' + (snapshot?.status ?? 'unknown'))
  }
  report.status = snapshot.status
  report.errorCode = snapshot.errorCode
  report.eventCount = snapshot.lastEventSeq

  const byId = new Map(snapshot.participants.map(member => [member.id, member]))
  const experts = snapshot.transcript.filter(line => line.kind === 'EXPERT')
  report.expertTurns = experts.length
  report.expertSpeeches = experts.map(line => ({
    seq: line.seq,
    speaker: byId.get(line.participantId)?.displayName ?? line.participantId,
    replyTo: line.replyToUtteranceSeq,
    content: line.content,
  }))
  report.opening = snapshot.transcript.find(line => line.kind === 'OPENING')?.content ?? null
  report.summary = snapshot.summary
  report.summarySentences = snapshot.summary ? snapshot.summary.split(/(?<=。)/).filter(Boolean).length : 0
  report.insights = snapshot.insights.map(insight => ({
    kind: insight.kind,
    text: insight.text,
    evidence: insight.evidenceUtteranceSeqs,
    evidenceSpeakers: [...new Set(insight.evidenceUtteranceSeqs
      .map(seq => experts.find(line => line.seq === seq)?.participantId)
      .filter(Boolean))].length,
  }))
  report.insightStatus = snapshot.insightStatus

  // 质量检查项：发言长度、引用本场发言、结论至少两位专家证据、主持人立场为空。
  const expertsMeta = snapshot.participants.filter(member => member.role === 'EXPERT')
  const hostMeta = snapshot.participants.find(member => member.role === 'HOST')
  report.checks = {
    hostStanceNull: hostMeta?.stance === null && hostMeta?.perspective === null,
    expertCountMatched: expertsMeta.length === 3,
    speechLengthInRange: experts.every(line => line.content.length >= 10 && line.content.length <= 200),
    replyReferencesPresent: experts.every(line => line.replyToUtteranceSeq === null || line.replyToUtteranceSeq < line.seq),
    distinctSpeakers: new Set(experts.map(line => line.participantId)).size,
    insightsTraceable: snapshot.insights.every(insight => insight.evidenceUtteranceSeqs
      .every(seq => experts.some(line => line.seq === seq))),
    insightSpeakersEnough: snapshot.insights.every(insight => new Set(insight.evidenceUtteranceSeqs
      .map(seq => experts.find(line => line.seq === seq)?.participantId)).size >= 2),
    summaryTwoSentences: report.summarySentences === 2,
  }
} catch (error) {
  report.error = error instanceof Error ? error.message : String(error)
  report.passed = false
} finally {
  stopBackend()
  await delay(800)
  await rm(databaseDir, { recursive: true, force: true }).catch(() => undefined)
  report.finishedAt = new Date().toISOString()
  report.passed = report.passed ?? (report.status === 'COMPLETED' && Object.values(report.checks ?? {}).every(Boolean))
  await writeFile(join(outputDir, 'real-model-report.json'), JSON.stringify(report, null, 2), 'utf8')
  console.log(JSON.stringify({
    outputDir,
    discussionId: report.discussionId,
    status: report.status,
    bootMillis: report.bootMillis,
    rosterMillis: report.rosterMillis,
    firstExpertMillis: report.firstExpertMillis,
    totalMillis: report.totalMillis,
    expertTurns: report.expertTurns,
    checks: report.checks,
    passed: report.passed,
    error: report.error,
  }, null, 2))
}

if (!report.passed) process.exitCode = 1
