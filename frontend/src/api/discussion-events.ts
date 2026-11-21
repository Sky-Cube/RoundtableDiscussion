import { publicFocusLabels, stageLabels } from './discussions'
import type { DiscussionSnapshot, Insight, Participant, Utterance } from './discussions'

type ParticipantStatus = Pick<Participant, 'stageStatus' | 'publicFocus' | 'publicSummary' | 'publicSpeech'> & { participantId: string }
type RoomStatus = Pick<DiscussionSnapshot, 'startedAt' | 'endedAt' | 'errorCode'> & {
  status: Exclude<DiscussionSnapshot['status'], 'DRAFT'>
}
type InsightUpdate = Pick<DiscussionSnapshot, 'insightsThroughUtteranceSeq' | 'insights'> & {
  insightStatus: 'FRESH' | 'STALE'
}
interface Payloads {
  'discussion.status': RoomStatus
  'participant.status': ParticipantStatus
  'utterance.added': Utterance
  'insight.updated': InsightUpdate
}
export type EventName = keyof Payloads
export const eventNames: EventName[] = ['discussion.status', 'participant.status', 'utterance.added', 'insight.updated']

type Fields = Record<string, unknown>
const utcPattern = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/
const stageTransitions = { IDLE: ['PREPARING'], PREPARING: ['SPEAKING', 'IDLE'], SPEAKING: ['SPEAKING', 'IDLE'] }
// 主持人不经过准备阶段，只会公开开场、串联和收尾的发言状态。
const hostTransitions: Record<Participant['stageStatus'], readonly string[]> = {
  IDLE: ['SPEAKING'],
  PREPARING: [],
  SPEAKING: ['SPEAKING', 'IDLE'],
}
const transitions: Record<DiscussionSnapshot['status'], readonly string[]> = {
  DRAFT: ['RUNNING'],
  RUNNING: ['SUMMARIZING', 'FAILED'],
  SUMMARIZING: ['COMPLETED', 'FAILED'],
  COMPLETED: [],
  FAILED: [],
}

function fields(value: unknown, required: string[], optional: string[] = []): value is Fields {
  return !!value && typeof value === 'object' && !Array.isArray(value)
    && required.every(key => Object.hasOwn(value, key))
    && Object.keys(value).every(key => required.includes(key) || optional.includes(key))
}

function sequence(value: unknown, min = 1): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= min
}

function utc(value: unknown): value is string {
  return typeof value === 'string' && utcPattern.test(value) && Number.isFinite(Date.parse(value))
}

function text(value: unknown, min: number, max: number): value is string {
  if (typeof value !== 'string' || !value.trim() || value.length > max * 2) return false
  const length = [...value].length
  return length >= min && length <= max
}

function isRoomStatus(value: unknown): value is RoomStatus {
  if (!fields(value, ['status', 'startedAt', 'endedAt', 'errorCode']) || !utc(value.startedAt)) return false
  if (value.status === 'RUNNING' || value.status === 'SUMMARIZING') {
    return value.endedAt === null && value.errorCode === null
  }
  if (!utc(value.endedAt) || Date.parse(value.endedAt) < Date.parse(value.startedAt)) return false
  if (value.status === 'COMPLETED') return value.errorCode === null
  return value.status === 'FAILED'
    && (value.errorCode === 500 || value.errorCode === 502 || value.errorCode === 503 || value.errorCode === 504)
}

function isParticipantStatus(value: unknown): value is ParticipantStatus {
  if (!fields(value, ['participantId', 'stageStatus', 'publicFocus'], ['publicSummary', 'publicSpeech'])) return false
  if (typeof value.participantId !== 'string' || typeof value.stageStatus !== 'string'
    || !Object.hasOwn(stageLabels, value.stageStatus)) return false
  if (value.stageStatus === 'IDLE') return value.publicFocus === null && value.publicSummary == null && value.publicSpeech == null
  return (value.publicFocus === null || publicFocusLabels.some(label => label === value.publicFocus))
    && (value.publicSummary == null || text(value.publicSummary, 1, 80))
    && (value.publicSpeech == null || (value.stageStatus === 'SPEAKING' && text(value.publicSpeech, 1, 120)))
}

function isUtterance(value: unknown): value is Utterance {
  if (!fields(value, ['seq', 'participantId', 'kind', 'content', 'replyToUtteranceSeq', 'createdAt'])
    || !sequence(value.seq) || typeof value.participantId !== 'string' || !utc(value.createdAt)) return false
  if (value.kind === 'SUMMARY' || value.kind === 'OPENING') {
    return value.replyToUtteranceSeq === null && text(value.content, 2, value.kind === 'SUMMARY' ? 200 : 120)
  }
  if (value.kind !== 'EXPERT' && value.kind !== 'FOLLOW_UP' && value.kind !== 'BRIDGE') return false
  return text(value.content, 2, 120)
    && (value.replyToUtteranceSeq === null ? value.kind !== 'BRIDGE' : sequence(value.replyToUtteranceSeq))
}

function isInsight(value: unknown): value is Insight {
  if (!fields(value, ['id', 'kind', 'text', 'evidenceUtteranceSeqs'])) return false
  const evidence = value.evidenceUtteranceSeqs
  return typeof value.id === 'string' && value.id.length > 0
    && (value.kind === 'CONSENSUS' || value.kind === 'DISAGREEMENT') && text(value.text, 4, 80)
    && Array.isArray(evidence) && evidence.length >= 2 && evidence.length <= 4
    && evidence.every(item => sequence(item)) && new Set(evidence).size === evidence.length
}

function isInsightUpdate(value: unknown): value is InsightUpdate {
  return fields(value, ['insightStatus', 'insightsThroughUtteranceSeq', 'insights'])
    && (value.insightStatus === 'FRESH' || value.insightStatus === 'STALE')
    && sequence(value.insightsThroughUtteranceSeq, value.insightStatus === 'FRESH' ? 1 : 0)
    && Array.isArray(value.insights) && value.insights.length <= 6 && value.insights.every(isInsight)
    && new Set(value.insights.map(item => item.id)).size === value.insights.length
    && (value.insightsThroughUtteranceSeq !== 0 || value.insights.length === 0)
}

export function readEvent(message: MessageEvent<string>, room: DiscussionSnapshot) {
  let frame: unknown
  try {
    frame = JSON.parse(message.data)
  } catch {
    return { kind: 'refresh' } as const
  }
  if (!fields(frame, ['discussionId', 'seq', 'occurredAt', 'payload']) || typeof frame.discussionId !== 'string') {
    return { kind: 'refresh' } as const
  }
  if (frame.discussionId !== room.id) return { kind: 'ignore' } as const
  if (!sequence(frame.seq) || message.lastEventId !== String(frame.seq) || !utc(frame.occurredAt)) {
    return { kind: 'refresh' } as const
  }
  if (frame.seq <= room.lastEventSeq) return { kind: 'ignore' } as const
  if (frame.seq !== room.lastEventSeq + 1) return { kind: 'refresh' } as const
  return { kind: 'apply', seq: frame.seq, payload: frame.payload } as const
}

export function applyEvent(room: DiscussionSnapshot, name: EventName, payload: unknown): DiscussionSnapshot | undefined {
  switch (name) {
    case 'discussion.status': {
      if (!isRoomStatus(payload) || !transitions[room.status].includes(payload.status)
        || (room.startedAt !== null && payload.startedAt !== room.startedAt)
        || (payload.status === 'COMPLETED' && room.summary === null)) return
      return { ...room, ...payload }
    }
    case 'participant.status': {
      if ((room.status !== 'RUNNING' && room.status !== 'SUMMARIZING') || !isParticipantStatus(payload)) return
      const member = room.participants.find(item => item.id === payload.participantId)
      if (!member) return
      const allowed = member.role === 'HOST' ? hostTransitions[member.stageStatus] : stageTransitions[member.stageStatus]
      if (!allowed.includes(payload.stageStatus)) return
      const { stageStatus, publicFocus, publicSummary, publicSpeech } = payload
      // 主持人只公开阶段状态，携带专属发言内容的事件按非法载荷处理。
      if (member.role === 'HOST') {
        if (publicSummary != null || publicSpeech != null) return
        return { ...room, participants: room.participants.map(item => item === member
          ? { ...item, stageStatus, publicFocus } : item) }
      }
      if (publicSpeech && member.publicSpeech && !publicSpeech.startsWith(member.publicSpeech)) return
      const participants = room.participants.map(item => item === member
        ? { ...item, stageStatus, publicFocus, publicSummary: publicSummary ?? null, publicSpeech: publicSpeech ?? null }
        : item)
      return { ...room, participants }
    }
    case 'utterance.added': {
      if (!isUtterance(payload) || payload.seq !== (room.transcript.at(-1)?.seq ?? 0) + 1
        || room.status !== (payload.kind === 'SUMMARY' ? 'SUMMARIZING' : 'RUNNING')) return
      const member = room.participants.find(item => item.id === payload.participantId)
      if (!member || (member.role === 'EXPERT') !== (payload.kind === 'EXPERT')) return
      if (payload.replyToUtteranceSeq !== null && !room.transcript.some(item => item.seq === payload.replyToUtteranceSeq
        && item.participantId !== payload.participantId)) return
      return { ...room, transcript: [...room.transcript, payload], summary: payload.kind === 'SUMMARY' ? payload.content : room.summary }
    }
    case 'insight.updated': {
      if (room.status !== 'RUNNING' || !isInsightUpdate(payload)) return
      if (payload.insightStatus === 'STALE') {
        const unchanged = payload.insights.length === room.insights.length && payload.insights.every((item, index) => {
          const previous = room.insights[index]
          return item.id === previous.id && item.kind === previous.kind && item.text === previous.text
            && item.evidenceUtteranceSeqs.length === previous.evidenceUtteranceSeqs.length
            && item.evidenceUtteranceSeqs.every((seq, offset) => seq === previous.evidenceUtteranceSeqs[offset])
        })
        return unchanged && payload.insightsThroughUtteranceSeq === room.insightsThroughUtteranceSeq
          ? { ...room, insightStatus: 'STALE' }
          : undefined
      }
      const latestExpert = room.transcript.filter(item => item.kind === 'EXPERT').at(-1)?.seq
      if (payload.insightsThroughUtteranceSeq !== latestExpert) return
      for (const insight of payload.insights) {
        const sources = insight.evidenceUtteranceSeqs.map(seq => room.transcript.find(item => item.seq === seq))
        if (sources.some(item => item?.kind !== 'EXPERT') || new Set(sources.map(item => item?.participantId)).size < 2) return
      }
      return { ...room, ...payload }
    }
  }
}
