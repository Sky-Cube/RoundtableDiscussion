export const goals = {
  EVALUATE: '评估',
  PROPOSE: '提案',
  SOLVE: '排查',
  EXPLORE: '探索',
} as const
export type DiscussionGoal = keyof typeof goals

export const perspectives = {
  EVIDENCE: '证据',
  DELIVERY: '交付',
  COST: '成本',
  RISK: '风险',
  USER_IMPACT: '用户影响',
  GOVERNANCE: '治理',
} as const
export type Perspective = keyof typeof perspectives

export const statusLabels = {
  DRAFT: '待确认',
  RUNNING: '讨论中',
  SUMMARIZING: '正在总结',
  COMPLETED: '已结束',
  FAILED: '讨论中断',
} as const
export type DiscussionStatus = keyof typeof statusLabels
type ActiveStatus = 'RUNNING' | 'SUMMARIZING'
export const stageLabels = { IDLE: '待机', PREPARING: '准备发言', SPEAKING: '发言中' } as const
export type StageStatus = keyof typeof stageLabels
export const publicFocusLabels = ['关注关键取舍', '检验前提假设', '关注证据依据', '关注风险与边界条件', '关注影响范围', '关注实施路径'] as const
export type PublicFocus = (typeof publicFocusLabels)[number]
export type DiscussionErrorCode = 'B0005' | 'B9001' | 'B9002'

export interface DiscussionListItem {
  id: string
  topic: string
  status: ActiveStatus
  expertCount: number
  startedAt: string
}

export type PresetParticipant = {
  id: string
  displayName: string
  profession: string
  title: string
  colorKey: string
} & (
  | { role: 'HOST'; stance: null; perspective: null }
  | { role: 'EXPERT'; stance: string; perspective: Perspective }
)

export type Participant = PresetParticipant & {
  stageStatus: StageStatus
  publicFocus: PublicFocus | null
  publicSummary?: string | null
  publicSpeech?: string | null
}

export interface Preset {
  slug: string
  topic: string
  discussionGoal: DiscussionGoal
  expertCount: number
  participants: PresetParticipant[]
}

export interface Utterance {
  seq: number
  participantId: string
  kind: 'OPENING' | 'EXPERT' | 'FOLLOW_UP' | 'BRIDGE' | 'SUMMARY'
  content: string
  replyToUtteranceSeq: number | null
  createdAt: string
}

export interface Insight {
  id: string
  kind: 'CONSENSUS' | 'DISAGREEMENT'
  text: string
  evidenceUtteranceSeqs: number[]
}

export interface DiscussionSnapshot {
  id: string
  topic: string
  discussionGoal: DiscussionGoal
  expertCount: number
  status: DiscussionStatus
  startedAt: string | null
  endedAt: string | null
  errorCode: DiscussionErrorCode | null
  participants: Participant[]
  transcript: Utterance[]
  insights: Insight[]
  insightStatus: 'PENDING' | 'FRESH' | 'STALE'
  insightsThroughUtteranceSeq: number
  summary: string | null
  lastEventSeq: number
}

const errors: Readonly<Record<string, string>> = {
  B0001: '请求参数无效，请检查后重试。',
  B0002: '该讨论或示例已找不到。',
  B0003: '这场讨论已经结束，可以查看房间。',
  B0004: '演播厅暂时满员，请稍后重试。',
  B0006: '此来源无法访问讨论服务。',
  B4001: '模型服务尚未配置，请稍后重试。',
  B9001: '暂时无法生成合格的专家阵容。可以重试，或从示例议题开始。',
  B9002: '生成阵容超时。可以重试，或从示例议题开始。',
}

export class ApiError extends Error {
  constructor(readonly code: string, readonly status: number) {
    super(Object.hasOwn(errors, code) ? errors[code] : '讨论服务暂时不可用，请稍后重试。')
  }
}

export function requestError(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

async function request<T>(path: string, signal: AbortSignal, method = 'GET', body?: object): Promise<T> {
  const response = await fetch('/api/v1' + path, {
    method,
    signal,
    headers: { Accept: 'application/json', ...(body && { 'Content-Type': 'application/json' }) },
    body: body && JSON.stringify(body),
  })
  const envelope = await response.json().catch(() => null)
  if (!response.ok || envelope?.code !== 'OK' || envelope.data == null) {
    throw new ApiError(typeof envelope?.code === 'string' ? envelope.code : '', response.status)
  }
  return envelope.data
}

type Draft = DiscussionSnapshot & { status: 'DRAFT' }

export const discussions = {
  listActive: (signal: AbortSignal) => request<DiscussionListItem[]>('/discussions?status=ACTIVE', signal),
  listPresets: (signal: AbortSignal) => request<Preset[]>('/presets', signal),
  create: (topic: string, expertCount: number, signal: AbortSignal) =>
    request<Draft>('/discussions', signal, 'POST', { topic, expertCount }),
  copyPreset: (slug: string, signal: AbortSignal) =>
    request<Draft>(`/presets/${encodeURIComponent(slug)}/discussions`, signal, 'POST'),
  get: (id: string, signal: AbortSignal) =>
    request<DiscussionSnapshot>(`/discussions/${encodeURIComponent(id)}`, signal),
  events: (id: string, after: number) =>
    new EventSource(`/api/v1/discussions/${encodeURIComponent(id)}/events?after=${after}`),
  start: (id: string, signal: AbortSignal) =>
    request<{ id: string; status: ActiveStatus }>(`/discussions/${encodeURIComponent(id)}/start`, signal, 'POST'),
}
