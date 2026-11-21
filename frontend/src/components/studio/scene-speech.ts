import type { Participant, StageStatus, Utterance } from '../../api/discussions'

export interface SceneSpeech {
  participantId: string
  content: string
  /** 是否仍在接收已审核的实时公开口播，false 表示展示已提交的完整发言 */
  live: boolean
}

/**
 * 解析演播厅当前展示的发言。
 * 优先展示正在发言成员公开的实时口播前缀，其次定格最近一条已提交发言；讨论结束后不再保留气泡。
 */
export function resolveSceneSpeech(
  participants: Participant[],
  bubble: Utterance | undefined,
  ended: boolean,
): SceneSpeech | undefined {
  // 讨论结束后不再保留气泡，让姓名牌完整显示
  if (ended) return undefined
  const active = participants.find(member => member.stageStatus === 'SPEAKING')
  if (active?.publicSpeech) {
    return { participantId: active.id, content: active.publicSpeech, live: true }
  }
  // 下一条发言尚在生成时先收起上一条气泡，避免同时出现两位“发言中”成员
  if (active || !bubble) return undefined
  return { participantId: bubble.participantId, content: bubble.content, live: false }
}

/**
 * 左侧成员卡片展示的阶段状态。
 * 气泡仍在演播厅展示时持续标记为发言中，避免发言中状态随事件一闪而过；
 * 成员自身处于准备或发言阶段时以真实阶段事件为准。
 */
export function displayStageStatus(member: Participant, speech: SceneSpeech | undefined): StageStatus {
  if (member.stageStatus !== 'IDLE') return member.stageStatus
  return speech?.participantId === member.id ? 'SPEAKING' : 'IDLE'
}
