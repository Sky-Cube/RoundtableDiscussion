/**
 * E2E 系统级测试使用的可控模型上游。
 *
 * 只在本轮临时进程内运行，通过 OpenAI 兼容的非流式 chat/completions 接口
 * 返回合成内容。测试可通过 /control 注入上游故障，用于验证错误映射与恢复。
 * 该进程不读取任何真实凭据，也不连接外部网络。
 */
import { createServer } from 'node:http'
import { once } from 'node:events'
import { setTimeout as sleep } from 'node:timers/promises'

const PORT = Number(process.env.FAKE_UPSTREAM_PORT ?? 18082)

/** 运行期控制面：故障次数、节奏缩放与上游调用计数。 */
const control = { failRoster: 0, failAll: 0, delayScale: 1, staleAtExpertTurns: 3, calls: 0 }

/** 从议题中提取本场标记，用于断言跨房间数据不串场。 */
function markerOf(topic) {
  const matched = typeof topic === 'string' ? topic.match(/系统[甲乙丙丁戊]场/) : null
  return matched ? matched[0] : '圆桌'
}

/** 组装阵容响应。专家人数与允许视角均由后端传入，避免模型自行决定。 */
function rosterReply(data) {
  return {
    discussionGoal: 'EVALUATE',
    participants: [
      {
        role: 'HOST', displayName: '系统主持', profession: '圆桌主持', title: '议题主持人',
        stance: null, perspective: null,
      },
      ...data.allowedPerspectives.slice(0, data.expertCount).map((perspective, index) => ({
        role: 'EXPERT',
        displayName: '系统专家' + (index + 1),
        profession: '客服运营',
        title: '服务流程顾问',
        stance: '优先核对第' + (index + 1) + '项验收条件，指标达标后再扩大试点范围',
        perspective,
      })),
    ],
  }
}

/** 按请求负载区分运行时任务，返回对应的合成输出。 */
function replyFor(data) {
  const room = markerOf(data.topic)
  const experts = data.utterances?.filter(line => line.kind === 'EXPERT') ?? []
  const turn = experts.length + 1

  if ('expertCount' in data) return rosterReply(data)

  if (data.phase === 'OPENING') {
    return { sentences: [room + '将讨论客服方案。', '请各位说明试点验收条件？'] }
  }

  if (data.phase === 'SUMMARY') {
    const first = experts[0]
    const second = experts.find(line => line.participantId !== first?.participantId)
    return {
      sentences: [room + '专家建议用试点指标验证方案。', '扩大范围前仍需观察实际指标。'],
      evidenceUtteranceSeqs: [first, second].filter(Boolean).map(line => line.seq),
    }
  }

  if ('mode' in data) {
    return { sentences: ['请各位说明验证条件。'], replyToUtteranceSeq: experts.at(-1)?.seq ?? null }
  }

  if ('previousInsights' in data) {
    const first = experts[0]
    const second = experts.find(line => line.participantId !== first?.participantId)
    if (!first || !second) return { insights: [] }
    // 第 3、4 条专家发言期间持续返回错误引文（含修复重试），
    // 使 STALE 状态保持数个发言轮次便于观察，第 5 条发言时恢复。
    const shouldStale = experts.length >= control.staleAtExpertTurns
      && experts.length < control.staleAtExpertTurns + 2
    return {
      insights: [{
        kind: 'CONSENSUS',
        text: room + '建议先验证试点指标',
        evidence: [first, second].map(line => ({
          utteranceSeq: line.seq,
          quote: shouldStale ? '这段证据故意使用错误引文' : '先用试点指标验证方案',
        })),
      }],
    }
  }

  if ('proposal' in data) {
    return { sentences: [room + '第' + turn + '轮关注实际交付条件。', '先用试点指标验证方案。'] }
  }

  const currentId = 'e' + String(data.expert?.displayName ?? '').replace('系统专家', '')
  const target = [...experts].reverse().find(line => line.participantId !== currentId)
  return {
    intent: target ? 'ADD' : 'RAISE',
    targetUtteranceSeq: target?.seq ?? null,
    focusCode: 'TRADE_OFF',
    proposedClaim: '需要比较第' + turn + '项验收条件。',
  }
}

const server = createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/control') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(control))
    return
  }
  if (req.method === 'POST' && req.url === '/control') {
    let body = ''
    for await (const chunk of req) body += chunk
    Object.assign(control, JSON.parse(body || '{}'))
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify(control))
    return
  }
  if (req.method === 'GET' && req.url === '/stats') {
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ calls: control.calls }))
    return
  }
  if (req.method !== 'POST' || !req.url?.endsWith('/chat/completions')) {
    res.writeHead(404).end('{}')
    return
  }

  try {
    let body = ''
    for await (const chunk of req) body += chunk
    const data = JSON.parse(JSON.parse(body).messages[1].content)
    control.calls += 1

    if (control.failAll > 0) {
      control.failAll -= 1
      res.writeHead(500, { 'Content-Type': 'application/json' }).end('{"error":"synthetic failure"}')
      return
    }
    if ('expertCount' in data && control.failRoster > 0) {
      control.failRoster -= 1
      res.writeHead(500, { 'Content-Type': 'application/json' }).end('{"error":"synthetic roster failure"}')
      return
    }

    const delay = (data.phase === 'OPENING' ? 900 : data.phase === 'SUMMARY' ? 1200 : 420) * control.delayScale
    await sleep(delay)
    res.writeHead(200, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({
      choices: [{ finish_reason: 'stop', message: { content: JSON.stringify(replyFor(data)) } }],
    }))
  } catch {
    res.writeHead(502, { 'Content-Type': 'application/json' }).end('{}')
  }
})

server.listen(PORT, '127.0.0.1')
await once(server, 'listening')
console.log('fake-upstream listening on ' + PORT)
