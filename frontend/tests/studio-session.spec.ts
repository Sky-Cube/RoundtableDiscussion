import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import fixture from '../../docs/contracts/api.fixtures.json'
import type { DiscussionSnapshot } from '../src/api/discussions'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router'

type Frame = (typeof fixture.frames.A)[number]
type FrameData = { discussionId: string; seq: number; occurredAt: string; payload: unknown }
const roomPath = (id: string) => '/discussions/' + id
const roomA = fixture.rooms.A
const roomB = fixture.rooms.B
const a = (id: number) => fixture.frames.A[id - 1]
const fetchMock = vi.fn<typeof fetch>()
const streams: FakeEventSource[] = []
let snapshots: Record<string, unknown>

class FakeEventSource extends EventTarget {
  close = vi.fn()
  onopen?: () => void
  onerror?: () => void

  constructor(readonly url: string) {
    super()
    streams.push(this)
  }

  send(event: string, data: FrameData, id = String(data.seq)) {
    this.dispatchEvent(new MessageEvent(event, { data: JSON.stringify(data), lastEventId: id }))
  }

  frame(frame: Frame) {
    this.send(frame.event, frame.data, frame.id)
  }
}

function response(data: unknown) {
  return new Response(JSON.stringify({ code: 200, data }))
}

async function open() {
  const router = createAppRouter(createMemoryHistory())
  await router.push(roomPath(roomA))
  const wrapper = mount(App, { attachTo: document.body, global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

enableAutoUnmount(afterEach)
beforeEach(() => {
  streams.length = 0
  snapshots = { [roomA]: fixture.snapshots.A2, [roomB]: fixture.snapshots.B2 }
  fetchMock.mockReset().mockImplementation(async path => response(snapshots[String(path).split('/').at(-1)!]))
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('EventSource', FakeEventSource)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('P04 房间会话与演播厅', () => {
  it('按快照游标订阅，忽略重复和其他房间事件，定位证据和回应', async () => {
    const { wrapper } = await open()
    expect(streams[0].url).toBe('/api/v1' + roomPath(roomA) + '/events?after=2')
    for (const frame of fixture.frames.A.slice(2, 12)) streams[0].frame(frame)
    streams[0].frame(a(10))
    streams[0].send(a(13).event, { ...a(13).data, discussionId: roomB })
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('[data-speech]')).toHaveLength(3)
    await wrapper.findAll('.citation').find(button => button.text() === '证据 #3')!.trigger('click')
    expect(document.activeElement?.getAttribute('data-speech')).toBe('3')
    await wrapper.findAll('.citation').find(button => button.text() === '回应 #2')!.trigger('click')
    expect(document.activeElement?.getAttribute('data-speech')).toBe('2')
  })

  it.each(['缺号', 'ID 不符', '非法载荷'])('发现%s后关闭旧流，以新快照续订', async (fault) => {
    const { wrapper } = await open()
    snapshots[roomA] = fixture.snapshots.A17
    if (fault === '缺号') streams[0].frame(a(4))
    else if (fault === 'ID 不符') streams[0].send(a(3).event, a(3).data, '4')
    else streams[0].send(a(3).event, { ...a(3).data, payload: { ...a(3).data.payload, hiddenReasoning: '合成测试字段' } })
    await flushPromises()
    expect(streams[0].close).toHaveBeenCalledTimes(1)
    expect(streams[1].url).toContain('after=17')
    streams[0].frame(a(18))
    expect(wrapper.get('h1').text()).toBe(fixture.snapshots.A17.topic)
    expect(wrapper.get('.studio-header__meta').text()).toContain('讨论中')
  })

  it('原生重连超时后有界重取快照，超过上限可手动恢复', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const { wrapper } = await open()
    streams[0].onopen?.()
    streams[0].onerror?.()
    await flushPromises()
    expect(wrapper.text()).toContain('正在重连')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    for (let index = 0; index < 3; index++) await vi.advanceTimersByTimeAsync(5000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(wrapper.text()).toContain('恢复连接')
  })

  it('换房关闭旧流，卸载取消待回快照，旧数据无法跳回页面', async () => {
    const { router, wrapper } = await open()
    let finish!: (value: Response) => void
    fetchMock.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    await router.push(roomPath(roomB))
    await flushPromises()
    expect(streams[0].close).toHaveBeenCalledTimes(1)
    const request = fetchMock.mock.calls.at(-1)?.[1]
    await router.push('/other')
    await flushPromises()
    expect(request?.signal?.aborted).toBe(true)
    finish(response(fixture.snapshots.B2))
    streams[0].frame(a(3))
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('页面未找到')
    expect(streams).toHaveLength(1)
  })

  it('发言期间应用公开关注与发言前缀，结束后保留完整文本', async () => {
    const { wrapper } = await open()
    const data = a(4).data
    streams[0].frame(a(3))
    streams[0].send('participant.status', { ...data, payload: {
      ...data.payload, publicSummary: '仅公开讨论的实施条件。', publicSpeech: 'A 可先采购完整方案',
    } })
    await flushPromises()
    expect(wrapper.get('.scene-caption').text()).toContain('正在发言陈简')
    expect(wrapper.get('.member-status-list [data-member="e1"]').text()).toContain('公开关注点：关注关键取舍')
    expect(wrapper.get('.seat-bubble--text p').text()).toBe('A 可先采购完整方案')
    expect(wrapper.findAll('[data-speech]')).toHaveLength(1)
    streams[0].frame(a(5))
    streams[0].frame(a(6))
    await flushPromises()
    expect(wrapper.get('.seat-bubble--text p').text()).toBe('A 可先采购完整方案，通过小流量验证首次解决率。')
    expect(wrapper.findAll('.seat-bubble--text')).toHaveLength(1)
    expect(wrapper.get('.member-status-list [data-member="e1"]').text()).not.toContain('仅公开讨论的实施条件。')
  })

  it('区分有效空结论、暂缓、恢复与终态', async () => {
    const { wrapper } = await open()
    for (const frame of fixture.frames.A.slice(2, 7)) streams[0].frame(frame)
    await flushPromises()
    expect(wrapper.text()).toContain('本轮暂无有足够证据')
    for (const frame of fixture.frames.A.slice(7, 17)) streams[0].frame(frame)
    await flushPromises()
    expect(wrapper.text()).toContain('观点更新暂缓')
    expect(wrapper.text()).toContain('先用小流量验证首次解决率')
    streams[0].send('insight.updated', {
      ...a(18).data,
      payload: { insightStatus: 'FRESH', insightsThroughUtteranceSeq: 4, insights: [] },
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('观点更新暂缓')
    expect(wrapper.text()).toContain('本轮暂无有足够证据')
    for (const frame of fixture.frames.A.slice(17)) streams[0].send(frame.event, { ...frame.data, seq: frame.data.seq + 1 })
    await flushPromises()
    expect(wrapper.find('.host-summary').exists()).toBe(true)
    expect(streams[0].close).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('已结束')
    await flushPromises()
    expect(wrapper.find('.result-dialog[open]').exists()).toBe(true)
    expect(wrapper.text()).toContain('本轮讨论')
    await wrapper.get('.result-modal__close').trigger('click')
    await flushPromises()
    expect(wrapper.find('.result-dialog').exists()).toBe(false)
  })

  it('直接打开失败终态仅读取快照和已公开实录', async () => {
    snapshots[roomA] = { ...fixture.snapshots.B9, id: roomA } as DiscussionSnapshot
    const { wrapper } = await open()
    expect(streams).toHaveLength(0)
    expect(wrapper.text()).toContain('讨论已中断')
    expect(wrapper.findAll('[data-speech]')).toHaveLength(fixture.snapshots.B9.transcript.length)
  })

  it('删除中断讨论前确认，成功后返回大厅', async () => {
    snapshots[roomA] = { ...fixture.snapshots.B9, id: roomA }
    const confirm = vi.fn().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('confirm', confirm)
    fetchMock.mockImplementation(async (path, options) => {
      if (options?.method === 'DELETE') return new Response(null, { status: 204 })
      return response(String(path).endsWith(roomA) ? snapshots[roomA] : [])
    })
    const { wrapper, router } = await open()
    const remove = wrapper.get('.studio-alerts button')
    await remove.trigger('click')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await remove.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1' + roomPath(roomA), expect.objectContaining({ method: 'DELETE' }))
    expect(router.currentRoute.value.name).toBe('lobby')
  })
})
