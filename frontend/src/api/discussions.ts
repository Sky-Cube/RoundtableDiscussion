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
export type DiscussionErrorCode = 500 | 502 | 503 | 504

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

const errors: Readonly<Partial<Record<number, string>>> = {
  400: '请求参数无效，请检查后重试。',
  403: '此来源无法访问讨论服务。',
  404: '该讨论或示例已找不到。',
  405: '不支持该请求方法。',
  406: '无法提供所要求的响应格式。',
  409: '这场讨论已经结束，可以查看房间。',
  415: '不支持该请求内容类型。',
  429: '演播厅暂时满员，请稍后重试。',
  502: '暂时无法生成合格的专家阵容。可以重试，或从示例议题开始。',
  503: '模型服务尚未配置，请稍后重试。',
  504: '生成阵容超时。可以重试，或从示例议题开始。',
}

export class ApiError extends Error {
  constructor(readonly status: number) {
    super(errors[status] ?? '讨论服务暂时不可用，请稍后重试。')
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
  if (!response.ok || envelope?.code !== response.status || envelope.data == null) {
    throw new ApiError(response.status)
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
    request<StartResult>(`/discussions/${encodeURIComponent(id)}/start`, signal, 'POST'),
}
/* ---------------- 后台管理接口 ---------------- */

export interface StartResult {
  id: string
  status: ActiveStatus | 'RUNNING' | 'SUMMARIZING'
}

export interface AdminOverview {
  discussionCounts: Record<DiscussionStatus, number>
  totalDiscussions: number
  presetCount: number
  utteranceCount: number
  insightCount: number
  eventCount: number
}

export interface AdminDiscussionRow {
  id: string
  topic: string
  discussionGoal: DiscussionGoal
  expertCount: number
  status: DiscussionStatus
  createdAt: string
  startedAt: string | null
  endedAt: string | null
  errorCode: DiscussionErrorCode | null
  lastEventSeq: number
  participantCount: number
  utteranceCount: number
  insightCount: number
  eventCount: number
}

export interface AdminEvent {
  seq: number
  type: string
  createdAt: string
  payload: unknown
}

export interface AdminRoom {
  snapshot: DiscussionSnapshot
  events: AdminEvent[]
}

export const admin = {
  overview: (signal: AbortSignal) => request<AdminOverview>('/admin/overview', signal),
  discussions: (status: string, keyword: string, date: string, signal: AbortSignal) => {
    const search = new URLSearchParams({ status })
    if (keyword.trim()) search.set('keyword', keyword.trim())
    if (date.trim()) search.set('date', date.trim())
    return request<AdminDiscussionRow[]>(`/admin/discussions?${search.toString()}`, signal)
  },
  room: (id: string, signal: AbortSignal) =>
    request<AdminRoom>(`/admin/discussions/${encodeURIComponent(id)}`, signal),
}
