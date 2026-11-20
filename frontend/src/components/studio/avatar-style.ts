import type { Participant, PresetParticipant } from '../../api/discussions'

export type AvatarStyle = 'anchor' | 'corporate' | 'anime' | 'creative' | 'scholar' | 'casual'

export const seatColors: Readonly<Record<string, string>> = {
  violet: '#bc97ed', blue: '#82b3e8', green: '#8bc695', amber: '#e4b36b',
  rose: '#e8a1bc', teal: '#71c8c0', cyan: '#7bbdcf', orange: '#ebab82',
}

export function seatColor(colorKey: string) {
  if (Object.hasOwn(seatColors, colorKey)) return seatColors[colorKey]
  const hue = [...colorKey].reduce((hash, character) => Math.imul(hash, 31) + character.codePointAt(0)!, 0) >>> 0
  return `hsl(${hue % 360}, 48%, 66%)`
}

export const avatarStyleLabels: Record<AvatarStyle, string> = {
  anchor: '主播',
  corporate: '职场',
  anime: '二次元',
  creative: '创意',
  scholar: '学院',
  casual: '休闲',
}

export const avatarStyleDescriptions: Record<AvatarStyle, string> = {
  anchor: '稳重的节目主持形象',
  corporate: '利落的职场西装形象',
  anime: '明快的二次元形象',
  creative: '松弛的创意工作者形象',
  scholar: '克制的研究者形象',
  casual: '自然的日常休闲形象',
}

const skinTones = ['#e7d2be', '#d8b99f', '#c79e7e', '#efddcb', '#bb8e6d', '#dfc4aa', '#d1aa8c']
const hairTones = ['#26211e', '#3a2b24', '#1e1d1c', '#4d392e', '#2a2421', '#3d2d24']

export function avatarStyleFor(participant: PresetParticipant, index: number): AvatarStyle {
  if (participant.role === 'HOST') return 'anchor'
  const text = `${participant.profession} ${participant.title} ${participant.perspective}`
  if (/隐私|法务|政策|安全|治理|研究|教育|心理|学术/u.test(text)) return 'scholar'
  if (/软件|产品|交互|设计|工程|技术/u.test(text)) return 'creative'
  if (/客服|内容|策展|观众|编辑|展览|文化|体验/u.test(text)) return 'anime'
  if (/运营|成本|商务|采购|管理|人事|空间|市场/u.test(text)) return 'corporate'
  return (['casual', 'corporate', 'creative', 'scholar'] as const)[index % 4]
}

export function avatarPalette(participant: PresetParticipant, index: number) {
  const skin = skinTones[index % skinTones.length]
  const hair = hairTones[index % hairTones.length]
  return { skin, hair, shadow: '#22303a' }
}

export function avatarInitial(participant: Pick<Participant, 'displayName'>) {
  return [...participant.displayName][0] ?? '？'
}
