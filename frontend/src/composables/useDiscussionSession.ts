import { onBeforeUnmount, onMounted, shallowRef, ref } from 'vue'
import { applyEvent, eventNames, readEvent } from '../api/discussion-events'
import { discussions, requestError } from '../api/discussions'
import type { DiscussionSnapshot, Utterance } from '../api/discussions'

const reconnectTimeout = 5000
const maxSnapshotRecoveries = 2
type Connection = 'loading' | 'connecting' | 'connected' | 'reconnecting' | 'offline' | 'closed'

export function useDiscussionSession(roomId: string) {
  const room = shallowRef<DiscussionSnapshot>()
  const connection = ref<Connection>('loading')
  const error = ref('')
  const bubble = shallowRef<Utterance>()
  let closed = false
  let pending: AbortController | undefined
  let source: EventSource | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let recoveries = 0

  function stopTimer() {
    clearTimeout(timer)
    timer = undefined
  }

  function closeStream() {
    const previous = source
    source = undefined
    previous?.close()
    stopTimer()
  }

  function recover() {
    closeStream()
    if (recoveries >= maxSnapshotRecoveries) {
      connection.value = 'offline'
      return
    }
    recoveries++
    void load()
  }

  function connect(snapshot: DiscussionSnapshot) {
    if (snapshot.status !== 'RUNNING' && snapshot.status !== 'SUMMARIZING') {
      connection.value = 'closed'
      return
    }

    connection.value = 'connecting'
    let stream: EventSource
    try {
      stream = discussions.events(roomId, snapshot.lastEventSeq)
    } catch {
      connection.value = 'offline'
      return
    }
    source = stream
    const armTimer = () => {
      timer ??= setTimeout(() => { if (source === stream) recover() }, reconnectTimeout)
    }
    armTimer()
    stream.onopen = () => {
      if (source !== stream) return
      stopTimer()
      connection.value = 'connected'
    }
    stream.onerror = () => {
      if (source !== stream) return
      connection.value = 'reconnecting'
      // 让 EventSource 优先使用浏览器记录的 Last-Event-ID 恢复。
      armTimer()
    }
    for (const name of eventNames) {
      stream.addEventListener(name, event => {
        const current = room.value
        if (source !== stream || !current) return
        const message = readEvent(event as MessageEvent<string>, current)
        if (message.kind === 'ignore') return
        if (message.kind === 'refresh') return recover()
        const next = applyEvent(current, name, message.payload)
        if (!next) return recover()

        recoveries = 0
        if (name === 'utterance.added') bubble.value = next.transcript.at(-1)
        room.value = { ...next, lastEventSeq: message.seq }
        if (next.status === 'FAILED' || next.status === 'COMPLETED') {
          closeStream()
          connection.value = 'closed'
        }
      })
    }
  }

  async function load() {
    if (closed || pending) return
    closeStream()
    const controller = new AbortController()
    pending = controller
    connection.value = room.value ? 'reconnecting' : 'loading'
    error.value = ''

    try {
      const snapshot = await discussions.get(roomId, controller.signal)
      if (controller.signal.aborted) return
      if (snapshot.id !== roomId || !Number.isSafeInteger(snapshot.lastEventSeq)
        || snapshot.lastEventSeq < (room.value?.lastEventSeq ?? 0)) {
        throw new Error('快照与当前房间或事件游标不匹配')
      }
      room.value = snapshot
      bubble.value = snapshot.participants.some(item => item.stageStatus !== 'IDLE')
        ? undefined
        : snapshot.transcript.at(-1)
      pending = undefined
      connect(snapshot)
    } catch (cause) {
      if (!controller.signal.aborted) {
        pending = undefined
        error.value = requestError(cause, '暂时无法读取这场讨论，请重试。')
        connection.value = 'offline'
      }
    }
  }

  function retry() {
    if (pending) return
    recoveries = 0
    void load()
  }

  onMounted(load)
  onBeforeUnmount(() => {
    closed = true
    pending?.abort()
    closeStream()
  })

  return { room, bubble, connection, error, retry }
}
