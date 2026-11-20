export const minTopicLength = 3
export const maxTopicLength = 120
export const defaultExpertCount = 4
export const expertCounts = [2, 3, 4, 5, 6] as const

export function normalizeTopic(topic: string) {
  return topic.normalize('NFC').replace(/^\uFEFF/u, '').replaceAll('\u200B', '').replace(/[\t\r\n]+/gu, ' ')
}

export function validateTopic(topic: string) {
  if (/[\p{Cc}\u202A-\u202E\u2066-\u2069\uD800-\uDFFF]/u.test(topic)) {
    return '议题包含不支持的字符，请删除后重试。'
  }
  const length = [...topic.trim()].length
  return length < minTopicLength || length > maxTopicLength ? '请输入 3–120 个字符的议题。' : ''
}
