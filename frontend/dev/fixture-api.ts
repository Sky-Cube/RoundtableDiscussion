import { randomUUID } from 'node:crypto'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Plugin } from 'vite'
import fixture from '../../docs/contracts/api.fixtures.json'
import type {
  DiscussionListItem,
  DiscussionSnapshot,
  Participant,
  Perspective,
  Preset,
  PublicFocus,
} from '../src/api/discussions'
import { applyEvent } from '../src/api/discussion-events'
import type { EventName } from '../src/api/discussion-events'
import { defaultExpertCount, expertCounts, normalizeTopic, validateTopic } from '../src/topic'
import { agendaFor } from './agenda'

const template = fixture.snapshots.A0 as DiscussionSnapshot
const presets = fixture.http.find(item => item.operation === 'listPresets' && item.response.status === 200)!
  .response.body!.data as Preset[]
const primaryPreset = presets.find(preset => preset.slug === 'ai-support-buy-build')!
const availableExperts = presets.flatMap(preset => preset.participants.filter(member => member.role === 'EXPERT'))
type FixtureFrame = { event: EventName; id: string; data: { discussionId: string; seq: number; occurredAt: string; payload: unknown } }

const focusByPerspective: Record<Perspective, PublicFocus> = {
  COST: '关注关键取舍',
  DELIVERY: '关注实施路径',
  EVIDENCE: '关注证据依据',
  RISK: '关注风险与边界条件',
  USER_IMPACT: '关注影响范围',
  GOVERNANCE: '关注证据依据',
}

const followUps: Record<Perspective, string[]> = {
  COST: ['先把首年总成本和退出条件算清，再讨论扩容。', '我担心统一采购会把后续迁移成本藏起来。'],
  DELIVERY: ['我建议先验证最小闭环，再决定自研范围。', '交付节奏取决于接口稳定性和人工兜底能力。'],
  EVIDENCE: ['请把口径写清，否则首轮数据无法比较。', '我会优先看可复核的失败样本，而不是平均值。'],
  RISK: ['高风险场景不能只靠一次试点就放开。', '需要预设回滚条件，避免问题扩散。'],
  USER_IMPACT: ['用户等待时间和申诉路径要一起看。', '无障碍和低带宽用户不能被排除在试点外。'],
  GOVERNANCE: ['责任边界要在上线前写进流程。', '谁审核、谁留痕、谁承担后果，需要明确。'],
}

const bridgeLines = [
  '我先打断一下，刚才两位都提到可验证指标，但对首版范围仍有不同判断。请后面发言的专家直接回应这个分歧。',
  '这里出现了一个新的取舍：速度与可控性并不总是同向。下一位请说明你会优先保住哪一项，以及为什么。',
  '我补充一个约束：本轮不追求一次定稿，先把可逆的实施路径和证据标准谈清楚。',
]

function speechFor(expert: Participant, turn: number, previous?: Participant) {
  const perspective = (expert.perspective ?? 'EVIDENCE') as Perspective
  const opening = previous ? `回应${previous.displayName}：` : turn === 0 ? '' : '补充一个角度：'
  const claim = (expert.stance ?? '需要先验证再扩大范围').replace(/[。！？!?]+$/u, '')
  const follow = followUps[perspective][turn % followUps[perspective].length]
  const text = `${opening}${claim}。${follow}`
  const clipped = [...text].slice(0, 116).join('').replace(/[，、；：]$/u, '')
  return /[。！？!?]$/u.test(clipped) ? clipped : clipped + '。'
}

function summaryFor(expert: Participant) {
  const perspective = expert.perspective ?? 'EVIDENCE'
  return `正在比较${expert.profession}视角下的${perspective === 'COST' ? '成本与退出条件' : perspective === 'RISK' ? '风险边界' : '可验证条件'}。`
}

function insightSet(speeches: { seq: number; participantId: string }[], names: Map<string, string>) {
  const latest = speeches.at(-1)?.seq
  if (!latest) return undefined
  const distinct: { seq: number; participantId: string }[] = []
  for (const speech of [...speeches].reverse()) {
    if (!distinct.some(item => item.participantId === speech.participantId)) distinct.push(speech)
    if (distinct.length === 4) break
  }
  if (distinct.length < 2) return undefined
  const first = distinct[0]!
  const second = distinct[1]!
  const third = distinct[2] ?? first
  const fourth = distinct[3] ?? second
  const insights = [
    {
      id: 'i-consensus-1',
      kind: 'CONSENSUS' as const,
      text: '各方都要求先设定可验证的成功指标，再扩大试点范围。',
      evidenceUtteranceSeqs: [first.seq, second.seq],
    },
    {
      id: 'i-disagreement-1',
      kind: 'DISAGREEMENT' as const,
      text: `${names.get(first.participantId) ?? '第一位专家'}与${names.get(second.participantId) ?? '第二位专家'}对首版范围仍有分歧。`,
      evidenceUtteranceSeqs: [second.seq, first.seq],
    },
  ]
  if (distinct.length >= 3) {
    insights.push({
      id: 'i-consensus-2',
      kind: 'CONSENSUS',
      text: '先保留人工复核与回滚路径，能够降低首轮上线风险。',
      evidenceUtteranceSeqs: [second.seq, third.seq],
    })
  }
  if (distinct.length >= 4) {
    insights.push({
      id: 'i-disagreement-2',
      kind: 'DISAGREEMENT',
      text: `${names.get(third.participantId) ?? '第三位专家'}更关注实施节奏，${names.get(fourth.participantId) ?? '第四位专家'}更强调用户影响。`,
      evidenceUtteranceSeqs: [third.seq, fourth.seq],
    })
  }
  return { insightStatus: 'FRESH' as const, insightsThroughUtteranceSeq: latest, insights }
}

function send(response: ServerResponse, status: number, data: unknown = null, code = 'OK') {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify({ code, message: code === 'OK' ? '成功' : '请求失败', data, timestamp: new Date().toISOString() }))
}

async function readBody(request: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of request) chunks.push(chunk)
  return Buffer.concat(chunks).toString('utf8')
}

function draft(preset: Preset): DiscussionSnapshot {
  return {
    ...structuredClone(template),
    id: randomUUID(),
    topic: preset.topic,
    discussionGoal: preset.discussionGoal,
    expertCount: preset.expertCount,
    participants: preset.participants.map(member => ({ ...member, stageStatus: 'IDLE', publicFocus: null })),
  }
}

function createCustomDraft(body: string) {
  const input = JSON.parse(body)
  const count = input?.expertCount === undefined ? defaultExpertCount : input.expertCount
  if (typeof input?.topic !== 'string' || !expertCounts.some(value => value === count)) return undefined
  const topic = normalizeTopic(input.topic)
  if (validateTopic(topic)) return undefined
  const participants = primaryPreset.participants.slice(0, count + 1)
  for (const colorKey of ['teal', 'orange']) {
    if (participants.length >= count + 1) break
    const candidate = availableExperts.find(expert => participants.every(member => member.perspective !== expert.perspective))
    if (candidate) participants.push({ ...candidate, id: `e${participants.length}`, colorKey })
  }
  return draft({ ...primaryPreset, topic: topic.trim(), expertCount: count, participants })
}

export function fixtureApi(): Plugin {
  const rooms = new Map<string, DiscussionSnapshot>([
    [fixture.rooms.A, structuredClone(fixture.snapshots.A17) as DiscussionSnapshot],
    [fixture.rooms.B, structuredClone(fixture.snapshots.B2) as DiscussionSnapshot],
  ])
  const published = new Map<string, FixtureFrame[]>([
    [fixture.rooms.A, fixture.frames.A.slice(0, 17) as FixtureFrame[]],
    [fixture.rooms.B, fixture.frames.B.slice(0, 2) as FixtureFrame[]],
  ])
  const listeners = new Map<string, Set<ServerResponse>>()
  const activeRuns = new Set<ReturnType<typeof setTimeout>>()

  function publish(id: string, event: EventName, payload: unknown) {
    const room = rooms.get(id)!
    const next = applyEvent(room, event, payload)
    if (!next) return
    const seq = room.lastEventSeq + 1
    const frame = { event, id: String(seq), data: { discussionId: id, seq, occurredAt: new Date().toISOString(), payload } }
    rooms.set(id, { ...next, lastEventSeq: seq })
    const history = published.get(id) ?? []
    history.push(frame)
    published.set(id, history)
    for (const listener of listeners.get(id) ?? []) {
      writeFrame(listener, frame)
      if (next.status === 'COMPLETED' || next.status === 'FAILED') listener.end()
    }
  }

  function wait(ms: number) {
    return new Promise<void>(resolve => {
      const task = setTimeout(() => {
        activeRuns.delete(task)
        resolve()
      }, ms)
      activeRuns.add(task)
    })
  }

  async function run(room: DiscussionSnapshot) {
    const startedAt = new Date().toISOString()
    const experts = room.participants.filter(member => member.role === 'EXPERT')
    if (!experts.length) return
    const names = new Map(room.participants.map(member => [member.id, member.displayName]))
    const host = room.participants.find(member => member.role === 'HOST')
    const order = agendaFor(experts.length)
    const expertSpeeches: { seq: number; participantId: string }[] = []
    let utteranceSeq = 0
    let previous: { participant: Participant; seq: number } | undefined

    publish(room.id, 'discussion.status', { status: 'RUNNING', startedAt, endedAt: null, errorCode: null })
    await wait(500)

    utteranceSeq += 1
    publish(room.id, 'utterance.added', {
      seq: utteranceSeq,
      participantId: host?.id ?? room.participants[0]!.id,
      kind: 'OPENING',
      content: '本场不按座位轮询。请各位在看到值得回应的观点时直接接话，先说明你的判断标准和证据出处。',
      replyToUtteranceSeq: null,
      createdAt: new Date().toISOString(),
    })
    await wait(700)

    for (const [turn, expertIndex] of order.entries()) {
      const expert = experts[expertIndex]
      if (!expert) continue
      const perspective = (expert.perspective ?? 'EVIDENCE') as Perspective
      const focus = focusByPerspective[perspective]
      const summary = summaryFor(expert)
      const content = speechFor(expert, turn, previous?.participant)
      const chunks = [
        [...content].slice(0, Math.max(12, Math.ceil([...content].length * 0.42))).join(''),
        [...content].slice(0, Math.max(20, Math.ceil([...content].length * 0.76))).join(''),
        content,
      ]

      publish(room.id, 'participant.status', {
        participantId: expert.id, stageStatus: 'PREPARING', publicFocus: focus, publicSummary: summary,
      })
      await wait(850)
      publish(room.id, 'participant.status', {
        participantId: expert.id, stageStatus: 'SPEAKING', publicFocus: focus, publicSummary: summary, publicSpeech: chunks[0],
      })
      await wait(240)
      publish(room.id, 'participant.status', {
        participantId: expert.id, stageStatus: 'SPEAKING', publicFocus: focus, publicSummary: summary, publicSpeech: chunks[1],
      })
      await wait(240)
      publish(room.id, 'participant.status', {
        participantId: expert.id, stageStatus: 'SPEAKING', publicFocus: focus, publicSummary: summary, publicSpeech: content,
      })
      await wait(260)

      utteranceSeq += 1
      publish(room.id, 'utterance.added', {
        seq: utteranceSeq,
        participantId: expert.id,
        kind: 'EXPERT',
        content,
        replyToUtteranceSeq: previous?.seq ?? null,
        createdAt: new Date().toISOString(),
      })
      expertSpeeches.push({ seq: utteranceSeq, participantId: expert.id })
      previous = { participant: expert, seq: utteranceSeq }
      await wait(380)
      publish(room.id, 'participant.status', {
        participantId: expert.id, stageStatus: 'IDLE', publicFocus: null,
      })

      if (expertSpeeches.length >= 2 && expertSpeeches.length % 2 === 0) {
        const insights = insightSet(expertSpeeches, names)
        if (insights) {
          publish(room.id, 'insight.updated', insights)
        }
      }

      if (turn === 2 && host) {
        utteranceSeq += 1
        publish(room.id, 'utterance.added', {
          seq: utteranceSeq,
          participantId: host.id,
          kind: 'BRIDGE',
          content: bridgeLines[Math.floor(turn / 2) % bridgeLines.length],
          replyToUtteranceSeq: previous?.seq ?? null,
          createdAt: new Date().toISOString(),
        })
        await wait(520)
      }
      await wait(420)
    }

    const finalInsights = insightSet(expertSpeeches, names)
    if (finalInsights) {
      publish(room.id, 'insight.updated', finalInsights)
    }
    await wait(500)

    publish(room.id, 'discussion.status', { status: 'SUMMARIZING', startedAt, endedAt: null, errorCode: null })
    await wait(600)

    const speakerNames = [...new Set(expertSpeeches.map(item => names.get(item.participantId)).filter(Boolean))]
    const summaryContent = [...`本轮没有按座次轮流发言，${speakerNames.join('、')}围绕可验证指标、首版范围和退出条件形成了暂定交集。共识是先小范围验证并保留人工复核；分歧集中在首版由谁承担验证成本。`].slice(0, 196).join('')
    utteranceSeq += 1
    publish(room.id, 'utterance.added', {
      seq: utteranceSeq,
      participantId: host?.id ?? room.participants[0]!.id,
      kind: 'SUMMARY',
      content: summaryContent,
      replyToUtteranceSeq: null,
      createdAt: new Date().toISOString(),
    })
    await wait(450)
    publish(room.id, 'discussion.status', {
      status: 'COMPLETED', startedAt, endedAt: new Date().toISOString(), errorCode: null,
    })
  }

  function writeFrame(response: ServerResponse, frame: FixtureFrame) {
    response.write(`id: ${frame.id}\nevent: ${frame.event}\ndata: ${JSON.stringify(frame.data)}\n\n`)
  }

  return {
    name: 'panel-local-fixture',
    configureServer(server) {
      server.httpServer?.once('close', () => {
        for (const task of activeRuns) clearTimeout(task)
      })
      server.middlewares.use('/api/v1', async (request, response) => {
        const { pathname, searchParams } = new URL(request.url ?? '/', 'http://127.0.0.1')
        const method = request.method
        if (method === 'GET' && pathname === '/discussions') {
          const active = [...rooms.values()].flatMap((room): DiscussionListItem[] => {
            const { id, topic, status, expertCount, startedAt } = room
            return (status === 'RUNNING' || status === 'SUMMARIZING') && startedAt
              ? [{ id, topic, status, expertCount, startedAt }]
              : []
          })
          active.sort((a, b) => b.startedAt.localeCompare(a.startedAt) || a.id.localeCompare(b.id))
          return send(response, 200, active)
        }
        if (method === 'GET' && pathname === '/presets') return send(response, 200, presets)

        const copy = pathname.match(/^\/presets\/([a-z0-9-]+)\/discussions$/)
        if (method === 'POST' && copy) {
          if (await readBody(request)) return send(response, 400, null, 'B0001')
          const preset = presets.find(item => item.slug === copy[1])
          if (!preset) return send(response, 404, null, 'B0002')
          const room = draft(preset)
          rooms.set(room.id, room)
          response.setHeader('Location', `/api/v1/discussions/${room.id}`)
          return send(response, 201, room)
        }
        if (method === 'POST' && pathname === '/discussions') {
          let room: DiscussionSnapshot | undefined
          try {
            room = createCustomDraft(await readBody(request))
          } catch {
            return send(response, 400, null, 'B0001')
          }
          if (!room) return send(response, 400, null, 'B0001')
          rooms.set(room.id, room)
          response.setHeader('Location', `/api/v1/discussions/${room.id}`)
          return send(response, 201, room)
        }

        const match = pathname.match(/^\/discussions\/([0-9a-f-]+)(\/start|\/events)?$/)
        const room = match && rooms.get(match[1])
        if (room && method === 'GET' && !match[2]) return send(response, 200, room)
        if (room && method === 'GET' && match[2] === '/events') {
          const header = request.headers['last-event-id']
          const after = searchParams.getAll('after')
          const raw = header ?? after[0] ?? '0'
          const cursor = Number(raw)
          if (typeof raw !== 'string' || !/^(0|[1-9]\d*)$/.test(raw) || !Number.isSafeInteger(cursor)
            || cursor > room.lastEventSeq || (header === undefined && after.length > 1)) return send(response, 400, null, 'B0001')
          const terminal = room.status === 'COMPLETED' || room.status === 'FAILED'
          if (terminal && cursor === room.lastEventSeq) {
            response.writeHead(204).end()
            return
          }
          response.writeHead(200, { 'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-cache, no-transform' })
          response.write(': 已连接\n\n')
          for (const frame of published.get(room.id) ?? []) if (frame.data.seq > cursor) writeFrame(response, frame)
          if (terminal) return response.end()
          const clients = listeners.get(room.id) ?? new Set<ServerResponse>()
          clients.add(response)
          listeners.set(room.id, clients)
          response.once('close', () => {
            clients.delete(response)
            if (!clients.size) listeners.delete(room.id)
          })
          return
        }
        if (room && method === 'POST' && match[2] === '/start') {
          if (await readBody(request)) return send(response, 400, null, 'B0001')
          if (room.status === 'DRAFT') {
            void run(room)
            return send(response, 202, { id: room.id, status: 'RUNNING' })
          }
          if (room.status === 'RUNNING' || room.status === 'SUMMARIZING') {
            return send(response, 202, { id: room.id, status: room.status })
          }
          return send(response, 409, null, 'B0003')
        }
        return send(response, 404, null, 'B0002')
      })
    },
  }
}
