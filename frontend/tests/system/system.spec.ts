/**
 * E2E 系统级测试：真实 Vue 前端 + 真实 Spring 后端 + 临时 SQLite + 模型替身。
 *
 * 用例编号与《AI圆桌演播厅-分阶段Prompt》E2E 阶段对应：
 * - E01 主链路：创建草稿 → 确认开场 → 专家发言 → 证据跳转 → 实时结论 → 收尾。
 * - E02 并行与旁观：两场同时运行、大厅旁观进入、房间级数据隔离、容量上限。
 * - E03 直播结论：主持人开场前无结论；运行中产出结论；提炼失败保留旧版并标记；随后恢复。
 * - E04 断线重连：离线期间继续累积事件，恢复后游标补齐，无缺号无重复。
 * - E05 容量与故障：模型上游失败映射、失败后可重试恢复、未授权 Origin 拒绝、无密钥泄露。
 * - E06 响应式：1920×1080 / 1366×768 / 390×844，内容区独立滚动、窄屏标签与键盘、引用定位。
 * - E07 预设幂等：六条预设、同一预设重复复制互不影响。
 */
import { expect, test } from '@playwright/test'
import type { Browser, BrowserContext, Page, TestInfo } from '@playwright/test'
import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'
import { OUTPUT_DIR, REPO_ROOT, SYSTEM, apiJson, controlUpstream } from './support/harness'

/** 后端讨论快照中本套件使用的字段。 */
interface Snapshot {
  id: string
  topic: string
  status: string
  expertCount: number
  participants: Array<{ id: string; role: string; displayName: string; stance: string | null; perspective: string | null }>
  insightStatus: string
  summary: string | null
  lastEventSeq: number
  transcript: Array<{
    seq: number
    kind: string
    participantId: string
    displayName: string
    content: string
    replyToUtteranceSeq: number | null
  }>
  insights: Array<{ kind: string; text: string; evidenceUtteranceSeqs: number[] }>
}

interface Preset {
  slug: string
  topic: string
  discussionGoal: string
  expertCount: number
  participants: Array<{ id: string; role: string; displayName: string; stance: string | null; perspective: string | null }>
}

interface ReplayFrame {
  seq: number
  discussionId: string
  payload: { kind?: string; status?: string; insightStatus?: string }
}

/** 轮询后端快照，直到满足业务条件。瞬时网络错误按未就绪处理并重试。 */
async function waitSnapshot(id: string, predicate: (snapshot: Snapshot) => boolean, timeoutMs = 90_000): Promise<Snapshot> {
  const deadline = Date.now() + timeoutMs
  let lastError: unknown
  while (Date.now() < deadline) {
    try {
      const { status, body } = await apiJson<Snapshot>('/api/v1/discussions/' + id)
      if (status === 200 && predicate(body.data)) return body.data
    } catch (cause) {
      lastError = cause
    }
    await sleep(150)
  }
  throw new Error('等待房间状态超时：' + id + (lastError ? '，最后错误：' + String(lastError) : ''))
}

/** 在页面上创建草稿并停在阵容预览。 */
async function createDraftFromUi(page: Page, topic: string, expertCount = 2): Promise<Snapshot> {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: /从示例议题开始|开始一场讨论/ })).toBeVisible({ timeout: 30_000 })
  await page.getByLabel('讨论议题').fill(topic)
  await page.getByLabel('专家人数').selectOption(String(expertCount))
  const creating = page.waitForResponse(
    response => response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/discussions',
  )
  await page.getByRole('button', { name: '生成专家阵容' }).click()
  const response = await creating
  expect(response.status()).toBe(201)
  const created = (await response.json()).data as Snapshot
  await expect(page).toHaveURL(new RegExp('/discussions/' + created.id + '/roster$'))
  return created
}

/** 在阵容预览页确认启动，进入演播厅。 */
async function startFromRoster(page: Page, id: string): Promise<void> {
  const starting = page.waitForResponse(response => response.url().endsWith('/discussions/' + id + '/start'))
  await page.getByRole('button', { name: '开始讨论' }).click()
  const response = await starting
  expect(response.status()).toBe(202)
  await expect(page).toHaveURL(new RegExp('/discussions/' + id + '$'))
}

/** 通过 API 创建草稿，减少纯编排用例的 UI 交互时间。 */
async function createDraftByApi(topic: string, expertCount: number): Promise<Snapshot> {
  const { status, body } = await apiJson<Snapshot>('/api/v1/discussions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ topic, expertCount }),
  })
  expect(status).toBe(201)
  return body.data
}

/** 读取终态房间的 SSE 回放，用于校验事件序号连续与终态顺序。 */
async function replayFrames(id: string): Promise<ReplayFrame[]> {
  const response = await fetch(SYSTEM.backendUrl + '/api/v1/discussions/' + id + '/events', {
    headers: { Accept: 'text/event-stream', 'Last-Event-ID': '0' },
  })
  if (response.status === 204) return []
  expect(response.status).toBe(200)
  const text = await response.text()
  return text
    .split('\n')
    .filter(line => line.startsWith('data:'))
    .map(line => JSON.parse(line.slice(5).trim()) as ReplayFrame)
}

/** 断言事件序号从 1 开始连续且无重复。 */
function expectContinuousSequences(frames: ReplayFrame[]): void {
  expect(frames.length).toBeGreaterThan(0)
  expect(frames.map(frame => frame.seq)).toEqual(frames.map((_, index) => index + 1))
}

test.describe.configure({ mode: 'default' })

test('E01/E03 主链路、实时结论、提炼失败保留与恢复', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  const topic = '系统甲场：客服机器人上线前的验证条件'
  const created = await createDraftFromUi(page, topic, 2)

  // 确认前只创建草稿：无开场、无事件、无结论。
  const draft = await waitSnapshot(created.id, snapshot => snapshot.status === 'DRAFT')
  expect(draft.transcript).toHaveLength(0)
  expect(draft.lastEventSeq).toBe(0)
  await expect(page.getByRole('button', { name: '开始讨论' })).toBeVisible()
  await expect(page.locator('.expert-grid article')).toHaveCount(2)

  await startFromRoster(page, created.id)

  // 主持人开场先于任何专家发言。
  const opening = await waitSnapshot(created.id, snapshot =>
    snapshot.transcript.some(line => line.kind === 'OPENING'),
  )
  expect(opening.transcript[0].kind).toBe('OPENING')

  // 专家准备/发言状态由真实事件驱动，小窗只展示当前一位发言人的公开关注点。
  await expect(page.locator('.member-status-list li[data-state="PREPARING"], .member-status-list li[data-state="SPEAKING"]').first())
    .toBeVisible({ timeout: 45_000 })
  await expect(page.locator('.seat-bubble--text')).toHaveCount(1, { timeout: 45_000 })

  // 运行中即可看到实时结论，且房间尚未进入终态。
  const insightCitation = page.locator('#studio-panel-insights .citation').first()
  await expect(insightCitation).toBeVisible({ timeout: 90_000 })
  const runningStatus = await page.locator('.studio-header [data-status]').getAttribute('data-status')
  expect(['RUNNING', 'SUMMARIZING']).toContain(runningStatus)
  const firstInsight = await page.locator('#studio-panel-insights').innerText()
  expect(firstInsight).toContain('建议先验证试点指标')

  // 提炼失败：保留上一版结论并提示暂缓，随后自动恢复。
  await expect(page.getByText('观点更新暂缓，保留上次结果。')).toBeVisible({ timeout: 90_000 })
  await expect(page.getByText('系统甲场建议先验证试点指标').first()).toBeVisible()
  await expect(page.getByText('观点更新暂缓，保留上次结果。')).toHaveCount(0, { timeout: 90_000 })

  // 证据跳转定位到对应实录条目。
  const citationText = await insightCitation.innerText()
  const citedSeq = citationText.match(/\d+/)?.[0]
  expect(citedSeq).toBeTruthy()
  await insightCitation.click()
  await expect(page.locator('[data-speech="' + citedSeq + '"]')).toBeFocused()

  // 收尾必须入库后房间才进入 COMPLETED，且总结事件先于终态事件。
  const completed = await waitSnapshot(created.id, snapshot => snapshot.status === 'COMPLETED', 150_000)
  expect(completed.summary).toBeTruthy()
  await expect(page.locator('.studio-header [data-status]')).toHaveAttribute('data-status', 'COMPLETED')

  const frames = await replayFrames(created.id)
  expectContinuousSequences(frames)
  const summaryIndex = frames.findIndex(frame => frame.payload.kind === 'SUMMARY')
  expect(summaryIndex).toBeGreaterThan(-1)
  expect(summaryIndex).toBeLessThan(frames.length - 1)
  expect(frames.at(-1)?.payload.status).toBe('COMPLETED')

  // 专家互动：至少一条发言引用了其他成员；连发情况作为观察证据记录。
  const experts = completed.transcript.filter(line => line.kind === 'EXPERT')
  expect(experts.length).toBeGreaterThanOrEqual(4)
  expect(experts.some(line => line.replyToUtteranceSeq !== null)).toBe(true)
  const consecutive = experts.some((line, index) =>
    index > 0 && experts[index - 1].participantId === line.participantId,
  )
  await testInfo.attach('e01-expert-turns', {
    body: JSON.stringify({ consecutiveSpeaks: consecutive, turns: experts.length }, null, 2),
    contentType: 'application/json',
  })

  // 终态弹窗展示入库总结：进入 COMPLETED 后自动弹出，关闭后可从页头重新打开。
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible({ timeout: 30_000 })
  await expect(dialog).toContainText(completed.summary ?? '')
  await page.keyboard.press('Escape')
  await expect(dialog).not.toBeVisible()
  await page.getByRole('button', { name: '查看最终结果' }).click()
  await expect(dialog).toBeVisible()
  await expect(dialog).toContainText(completed.summary ?? '')
})

test('E02/E04 双房间并行、旁观、隔离、断线补齐与容量恢复', async ({ browser }, testInfo) => {
  test.setTimeout(360_000)
  const contextA = await browser.newContext({ reducedMotion: 'reduce' })
  const contextB = await browser.newContext({ reducedMotion: 'reduce' })
  const pageA = await contextA.newPage()
  const pageB = await contextB.newPage()
  const topicA = '系统乙场：订单异常处理的验收条件'
  const topicB = '系统丙场：会员续费提醒的验收条件'

  const draftA = await createDraftFromUi(pageA, topicA, 2)
  const draftB = await createDraftFromUi(pageB, topicB, 2)
  await startFromRoster(pageA, draftA.id)
  await startFromRoster(pageB, draftB.id)

  // 容量上限为 2：两场刚启动必然都在 RUNNING，第三场确认应被拒绝且草稿保留。
  const presets = await apiJson<Preset[]>('/api/v1/presets')
  const heldPreset = presets.body.data.find(preset => preset.expertCount === 2) ?? presets.body.data[0]
  const held = await apiJson<Snapshot>('/api/v1/presets/' + heldPreset.slug + '/discussions', { method: 'POST' })
  expect(held.status).toBe(201)
  const rejected = await apiJson<unknown>('/api/v1/discussions/' + held.body.data.id + '/start', { method: 'POST' })
  expect(rejected.status).toBe(429)
  expect(rejected.body.code).toBe(429)
  expect((await waitSnapshot(held.body.data.id, snapshot => snapshot.status === 'DRAFT')).lastEventSeq).toBe(0)

  // 断线：离线期间后端继续推进，恢复后新连接按游标补齐。
  const beforeOffline = await waitSnapshot(draftA.id, snapshot => snapshot.transcript.some(line => line.kind === 'EXPERT'))
  await contextA.setOffline(true)
  const advanced = await waitSnapshot(draftA.id, snapshot => snapshot.lastEventSeq >= beforeOffline.lastEventSeq + 5, 60_000)
  await contextA.setOffline(false)

  // 恢复网络后优先自动重连；若已进入“连接中断”，通过页面提供的恢复入口重建连接。
  const latestText = advanced.transcript.at(-1)!.content
  await pageA.waitForTimeout(7000)
  const recoveryButton = pageA.getByRole('button', { name: /恢复连接|重新连接|重连/ })
  if (!(await pageA.locator('.transcript-scroll').innerText()).includes(latestText)
    && await recoveryButton.isVisible().catch(() => false)) {
    await recoveryButton.click()
  }
  await expect(pageA.locator('.transcript-scroll')).toContainText(latestText, { timeout: 60_000 })
  const framesBeforeReload = await waitSnapshot(draftA.id, () => true)
  expect(framesBeforeReload.transcript.every((line, index, lines) =>
    index === 0 || line.seq > lines[index - 1].seq,
  )).toBe(true)

  // 大厅列出两个进行中的房间，旁观进入既有房间而不是新开。
  const lobby = await browser.newPage()
  await lobby.goto('/')
  await expect(lobby.getByRole('link', { name: /旁观/ }).filter({ hasText: '系统乙场' })).toBeVisible({ timeout: 30_000 })
  await expect(lobby.getByRole('link', { name: /旁观/ }).filter({ hasText: '系统丙场' })).toBeVisible()
  await lobby.getByRole('link', { name: /旁观/ }).filter({ hasText: '系统乙场' }).click()
  await expect(lobby).toHaveURL(new RegExp('/discussions/' + draftA.id + '$'), { timeout: 45_000 })
  await expect(lobby.locator('body')).not.toContainText('系统丙场')
  await lobby.close()

  // 重新建立连接后按快照游标回放，事件序号连续且不重复。
  await pageA.reload()
  await expect(pageA.locator('.studio-header [data-status]')).toHaveAttribute('data-status', /RUNNING|SUMMARIZING|COMPLETED/, { timeout: 30_000 })
  const afterReload = await waitSnapshot(draftA.id, snapshot => snapshot.status === 'COMPLETED', 150_000)
  expect(afterReload.lastEventSeq).toBeGreaterThanOrEqual(framesBeforeReload.lastEventSeq)
  const frames = await replayFrames(draftA.id)
  expectContinuousSequences(frames)
  expect(frames.every(frame => frame.discussionId === draftA.id)).toBe(true)

  // 房间级隔离：任一侧都不得出现另一场的标记文本。
  await expect(pageA.locator('body')).not.toContainText('系统丙场')
  await expect(pageB.locator('body')).not.toContainText('系统乙场')

  // 两场结束后槽位释放，此前被拒的草稿可以重新确认。
  const completedB = await waitSnapshot(draftB.id, snapshot => snapshot.status === 'COMPLETED', 150_000)
  expect(completedB.summary).toBeTruthy()
  // 终态提交与槽位释放存在毫秒级先后，重试允许短暂等待后必须成功。
  let retried = await apiJson<unknown>('/api/v1/discussions/' + held.body.data.id + '/start', { method: 'POST' })
  const releaseDeadline = Date.now() + 30_000
  while (retried.status === 429 && Date.now() < releaseDeadline) {
    await sleep(500)
    retried = await apiJson<unknown>('/api/v1/discussions/' + held.body.data.id + '/start', { method: 'POST' })
  }
  expect(retried.status).toBe(202)
  const finalHeld = await waitSnapshot(held.body.data.id, snapshot => snapshot.status === 'COMPLETED', 150_000)
  expect(finalHeld.summary).toBeTruthy()

  await testInfo.attach('e02-isolation', {
    body: JSON.stringify({
      roomA: { id: draftA.id, events: afterReload.lastEventSeq, summary: afterReload.summary },
      roomB: { id: draftB.id, events: completedB.lastEventSeq, summary: completedB.summary },
      capacityRejected: rejected.body.code,
    }, null, 2),
    contentType: 'application/json',
  })

  await contextA.close()
  await contextB.close()
})

test('E05 模型上游失败映射、重试恢复、Origin 拒绝与密钥哨兵', async ({ browser, page }, testInfo) => {
  test.setTimeout(180_000)
  const key = SYSTEM.apiKey
  const leaks: string[] = []
  page.on('request', request => {
    const raw = request.url() + JSON.stringify(request.headers())
    if (raw.includes(key)) leaks.push('request ' + request.url())
  })
  page.on('response', async response => {
    const contentType = response.headers()['content-type'] ?? ''
    if (!/json|javascript|text\/html|text\/plain/.test(contentType)) return
    try {
      if ((await response.text()).includes(key)) leaks.push('response ' + response.url())
    } catch {
      // 已关闭或无法读取的响应不参与哨兵。
    }
  })

  // 上游持续失败时创建应被拒绝，表单保留用户输入；上游恢复后重试成功。
  await controlUpstream({ failRoster: 20 })
  await page.goto('/')
  await page.getByLabel('讨论议题').fill('系统丁场：退款时效的验证条件')
  await page.getByLabel('专家人数').selectOption('2')
  const failing = page.waitForResponse(response =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/discussions',
  )
  await page.getByRole('button', { name: '生成专家阵容' }).click()
  expect((await failing).status()).toBe(502)
  await expect(page.getByRole('alert')).toContainText(/失败|稍后|重试/)
  await expect(page.getByLabel('讨论议题')).toHaveValue('系统丁场：退款时效的验证条件')

  await controlUpstream({ failRoster: 0 })
  const recovered = page.waitForResponse(response =>
    response.request().method() === 'POST' && new URL(response.url()).pathname === '/api/v1/discussions',
  )
  await page.getByRole('button', { name: '生成专家阵容' }).click()
  const recoveredResponse = await recovered
  expect(recoveredResponse.status()).toBe(201)
  await expect(page.locator('.expert-grid article')).toHaveCount(2)

  // 非白名单 Origin 的写请求被拒绝。
  const forbidden = await fetch(SYSTEM.backendUrl + '/api/v1/presets/' + (await apiJson<Preset[]>('/api/v1/presets')).body.data[0].slug + '/discussions', {
    method: 'POST',
    headers: { Origin: 'https://external.example' },
  })
  expect(forbidden.status).toBe(403)
  expect((await forbidden.json()).code).toBe(403)

  // 密钥哨兵：前端源码、后端日志与本次浏览器流量均不得出现真实密钥值。
  const sourceFiles = await readdir(join(REPO_ROOT, 'frontend', 'src'), { recursive: true })
  for (const file of sourceFiles) {
    if (!/\.(ts|vue|js|css)$/.test(file)) continue
    const content = await readFile(join(REPO_ROOT, 'frontend', 'src', file), 'utf8')
    expect(content.includes(key), '前端源码泄露密钥：' + file).toBe(false)
    expect(content.includes('PANEL_AI_API_KEY'), '前端源码引用服务端密钥变量：' + file).toBe(false)
  }
  const appLog = await readFile(join(OUTPUT_DIR, 'backend-app.log'), 'utf8')
  expect(appLog.includes(key)).toBe(false)
  expect(leaks).toEqual([])

  await testInfo.attach('e05-sentinels', {
    body: JSON.stringify({ leaks, sourceFiles: sourceFiles.length }, null, 2),
    contentType: 'application/json',
  })
})

test('E06 三视口布局、独立滚动、窄屏键盘与引用定位', async ({ browser, page }, testInfo) => {
  test.setTimeout(300_000)
  const topic = '系统戊场：客服质检抽样的验收条件'
  const draft = await createDraftByApi(topic, 2)
  const started = await apiJson<{ id: string; status: string }>('/api/v1/discussions/' + draft.id + '/start', { method: 'POST' })
  expect(started.status, 'E06 需要成功占用讨论槽位').toBe(202)

  const viewports = [
    { name: '1920x1080', width: 1920, height: 1080 },
    { name: '1366x768', width: 1366, height: 768 },
    { name: '390x844', width: 390, height: 844 },
  ]
  const contexts: Array<{ context: BrowserContext; page: Page; name: string }> = []
  for (const viewport of viewports) {
    const context = await browser.newContext({ viewport: { width: viewport.width, height: viewport.height }, reducedMotion: 'reduce' })
    const studioPage = await context.newPage()
    const errors: string[] = []
    studioPage.on('pageerror', error => errors.push(error.message))
    await studioPage.goto('/discussions/' + draft.id)
    contexts.push({ context, page: studioPage, name: viewport.name })
    await expect(studioPage.locator('.virtual-seat')).toHaveCount(3, { timeout: 30_000 })
    const layout = await studioPage.locator('#main-content').evaluate(element => {
      const style = getComputedStyle(element)
      return {
        horizontalOverflow: element.scrollWidth - element.clientWidth,
        verticalOverflow: style.overflowY,
        documentOverflow: document.documentElement.scrollHeight - window.innerHeight,
        memberListOverflow: getComputedStyle(document.querySelector('.member-status-list')!).overflowY,
      }
    })
    expect(layout.horizontalOverflow, viewport.name + ' 出现横向溢出').toBeLessThanOrEqual(0)
    expect(layout.verticalOverflow).toBe('hidden')
    expect(layout.documentOverflow).toBeLessThanOrEqual(0)
    expect(layout.memberListOverflow).toBe('auto')
    expect(errors).toEqual([])
  }

  const mobile = contexts.find(item => item.name === '390x844')!
  await mobile.page.getByRole('tab', { name: '现场' }).focus()
  await mobile.page.keyboard.press('ArrowRight')
  await expect(mobile.page.getByRole('heading', { name: '圆桌成员状态' })).toBeVisible()
  await mobile.page.keyboard.press('End')
  await expect(mobile.page.getByRole('tab', { name: '结论' })).toBeFocused()
  await mobile.page.keyboard.press('Enter')
  await expect(mobile.page.locator('#studio-panel-insights')).toBeVisible()

  const completed = await waitSnapshot(draft.id, snapshot => snapshot.status === 'COMPLETED', 180_000)
  expect(completed.summary).toBeTruthy()

  // 终态后实录区独立滚动，引用点击定位到对应条目。
  for (const item of contexts) {
    // 进入 COMPLETED 时结果弹窗会自动打开，先关闭避免遮挡演播厅与引用按钮。
    const resultDialog = item.page.getByRole('dialog')
    if (await resultDialog.isVisible().catch(() => false)) {
      await item.page.keyboard.press('Escape')
      await expect(resultDialog).not.toBeVisible()
    }
    const overflow = await item.page.locator('.transcript-scroll').evaluate(element => getComputedStyle(element).overflowY)
    expect(overflow).toBe('auto')
    await item.page.screenshot({ path: join(OUTPUT_DIR, 'e06-' + item.name + '.png'), fullPage: false })
  }
  const citation = mobile.page.locator('#studio-panel-insights .citation').first()
  await expect(citation).toBeVisible()
  const citedSeq = (await citation.innerText()).match(/\d+/)?.[0]
  await citation.click()
  await expect(mobile.page.locator('[data-speech="' + citedSeq + '"]')).toBeFocused()
  await mobile.page.screenshot({ path: join(OUTPUT_DIR, 'e06-390x844-citation.png'), fullPage: false })

  for (const item of contexts) await item.context.close()
})

test('E07 预设清单与重复复制隔离', async ({ page }) => {
  test.setTimeout(120_000)
  const presets = await apiJson<Preset[]>('/api/v1/presets')
  expect(presets.status).toBe(200)
  // 初始化脚本内置 6 条预设，且 slug 唯一、每条都有完整阵容。
  expect(presets.body.data.length).toBe(6)
  expect(new Set(presets.body.data.map(preset => preset.slug)).size).toBe(presets.body.data.length)
  for (const preset of presets.body.data) {
    expect(preset.participants).toHaveLength(preset.expertCount + 1)
    expect(preset.participants[0].role).toBe('HOST')
    expect(preset.participants[0].stance).toBeNull()
    expect(preset.participants[0].perspective).toBeNull()
    expect(preset.participants.slice(1).every(member => member.stance && member.perspective)).toBe(true)
  }

  const before = await (await fetch(SYSTEM.upstreamUrl + '/stats')).json()
  const first = await apiJson<Snapshot>('/api/v1/presets/' + presets.body.data[0].slug + '/discussions', { method: 'POST' })
  const second = await apiJson<Snapshot>('/api/v1/presets/' + presets.body.data[0].slug + '/discussions', { method: 'POST' })
  expect(first.status).toBe(201)
  expect(second.status).toBe(201)
  expect(first.body.data.id).not.toBe(second.body.data.id)
  expect(first.body.data.topic).toBe(second.body.data.topic)
  expect(first.body.data.status).toBe('DRAFT')
  expect(second.body.data.status).toBe('DRAFT')
  expect(first.body.data.lastEventSeq).toBe(0)
  expect(second.body.data.lastEventSeq).toBe(0)
  const after = await (await fetch(SYSTEM.upstreamUrl + '/stats')).json()
  expect(after.calls).toBe(before.calls)

  // 两次复制保持预设阵容内容一致，且各自是独立房间。
  const rosterA = await apiJson<Snapshot>('/api/v1/discussions/' + first.body.data.id)
  const rosterB = await apiJson<Snapshot>('/api/v1/discussions/' + second.body.data.id)
  expect(rosterA.body.data.participants).toHaveLength(presets.body.data[0].expertCount + 1)
  expect(rosterB.body.data.participants.map(member => member.displayName))
    .toEqual(rosterA.body.data.participants.map(member => member.displayName))
  expect(rosterA.body.data.participants[0].role).toBe('HOST')

  // 大厅预设区展示全部示例，复制入口可交互。
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '从示例议题开始' })).toBeVisible()
  // 预设卡片在接口返回后渲染，用会重试的断言等待数量稳定。
  await expect(page.locator('.preset-button')).toHaveCount(presets.body.data.length)
  await expect(page.locator('.preset-button').first()).toContainText('查看阵容')
  await page.locator('.preset-button').first().click()
  await expect(page).toHaveURL(/\/discussions\/[^/]+\/roster$/)
  for (const member of presets.body.data[0].participants) {
    await expect(page.getByText(member.displayName, { exact: true })).toBeVisible()
  }
})
