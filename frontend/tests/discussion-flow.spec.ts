import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory } from 'vue-router'
import fixture from '../../docs/contracts/api.fixtures.json'
import App from '../src/App.vue'
import { createAppRouter } from '../src/router'

const draft = fixture.http[4].response.body!.data as typeof fixture.snapshots.A0
const copiedDraft = fixture.http[13].response.body!.data as typeof fixture.snapshots.A0
const emptyRooms = fixture.http[1].response
const activeRooms = fixture.http[0].response
const presets = fixture.http[11].response
const startAccepted = fixture.http[15].response
const startPath = `/api/v1/discussions/${draft.id}/start`
const rosterPath = `/discussions/${draft.id}/roster`
const fetchMock = vi.fn<(path: string, init: RequestInit) => Promise<Response>>()

enableAutoUnmount(afterEach)

function response(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(data), { status }))
}

function fromFixture(item: { status: number; body?: unknown }) {
  return response(item.body, item.status)
}

function ok(data: unknown) {
  return response({ code: 'OK', message: '成功', data, timestamp: '2026-11-20T02:00:45Z' })
}

function deferredResponse() {
  let resolve!: (value: Response | PromiseLike<Response>) => void
  const promise = new Promise<Response>(complete => { resolve = complete })
  return { promise, resolve }
}

function posts() {
  return fetchMock.mock.calls.filter(([, init]) => init.method === 'POST')
}

async function open(path = '/') {
  const router = createAppRouter(createMemoryHistory())
  await router.push(path)
  const wrapper = mount(App, { global: { plugins: [router] } })
  return { router, wrapper }
}

beforeEach(() => {
  fetchMock.mockReset()
  fetchMock.mockImplementation((path, init) => {
    if (path === '/api/v1/discussions?status=ACTIVE') return fromFixture(emptyRooms)
    if (path === '/api/v1/presets') return fromFixture(presets)
    if (path === '/api/v1/discussions' && init.method === 'POST') return fromFixture(fixture.http[4].response)
    if (path === `/api/v1/discussions/${draft.id}`) return ok(draft)
    if (path === startPath && init.method === 'POST') return fromFixture(startAccepted)
    throw new Error(`未声明的测试请求 ${init.method} ${path}`)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => vi.unstubAllGlobals())

describe('P03 大厅与阵容', () => {
  it('显示加载与空态，活动房间读取失败后可以重试', async () => {
    const first = deferredResponse()
    fetchMock.mockImplementationOnce(() => first.promise)
    const { wrapper } = await open()
    expect(wrapper.text()).toContain('正在加载房间')
    expect(wrapper.text()).toContain('正在加载示例')
    first.resolve(fromFixture({ status: 500, body: fixture.http[12].response.body }))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时不可用')
    const retry = wrapper.findAll('button').find(button => button.text() === '重试加载房间')!
    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂时没有进行中的讨论')
    expect(wrapper.text()).toContain('发起一场讨论')
    expect(wrapper.findAll('.preset-button')).toHaveLength(5)
  })

  it.each([2, 121])('拒绝含 %s 个 Unicode 码点的议题', async (length) => {
    const { wrapper } = await open()
    await wrapper.get('textarea').setValue('😀'.repeat(length))
    await wrapper.get('.new-discussion form').trigger('submit')
    expect(wrapper.get('#topic-error').text()).toContain('3–120')
    expect(posts()).toHaveLength(0)
  })

  it.each([3, 120])('接受含 %s 个 Unicode 码点的议题和默认四位专家', async (length) => {
    const { wrapper, router } = await open()
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('4')
    const topic = '😀'.repeat(length)
    await wrapper.get('textarea').setValue(`  ${topic}  `)
    await wrapper.get('.new-discussion form').trigger('submit')
    await flushPromises()
    expect(posts()).toHaveLength(1)
    expect(JSON.parse(String(posts()[0][1].body))).toEqual({ topic, expertCount: 4 })
    expect(router.currentRoute.value.path).toBe(rosterPath)
  })

  it('失败保留议题与人数，提交期间拦截重复生成和预设复制', async () => {
    const pending = deferredResponse()
    const baseFetch = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((path, init) => path === '/api/v1/discussions' ? pending.promise : baseFetch(path, init))
    const { wrapper } = await open()
    await flushPromises()
    const topic = 'Cafe\u0301\t换行\n示例'
    await wrapper.get('textarea').setValue(topic)
    await wrapper.get('select').setValue('6')
    await wrapper.get('.new-discussion form').trigger('submit')
    await wrapper.get('.new-discussion form').trigger('submit')
    await wrapper.get('.preset-button').trigger('click')
    expect(posts()).toHaveLength(1)
    expect(JSON.parse(String(posts()[0][1].body))).toEqual({ topic: 'Café 换行 示例', expertCount: 6 })
    expect(wrapper.get('.new-discussion button').attributes('aria-disabled')).toBe('true')
    pending.resolve(fromFixture(fixture.http[7].response))
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时无法生成合格的专家阵容')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(topic)
    expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('6')
    expect(wrapper.get('.new-discussion button').attributes('aria-disabled')).toBe('false')
  })

  it('点击预设只复制草稿并展示预设讨论类型', async () => {
    const baseFetch = fetchMock.getMockImplementation()!
    const copyPath = '/api/v1/presets/hybrid-work-policy/discussions'
    fetchMock.mockImplementation((path, init) => {
      if (path === copyPath) return fromFixture(fixture.http[13].response)
      if (path === `/api/v1/discussions/${copiedDraft.id}`) return ok(copiedDraft)
      return baseFetch(path, init)
    })
    const { wrapper, router } = await open()
    await flushPromises()
    const presetButton = wrapper.findAll('button.preset-button').find(button => button.text().includes(copiedDraft.topic))!
    await presetButton.trigger('click')
    await flushPromises()
    expect(posts().map(([path]) => path)).toEqual([copyPath])
    expect(posts()[0][1].body).toBeUndefined()
    expect(router.currentRoute.value.path).toBe(`/discussions/${copiedDraft.id}/roster`)
    expect(wrapper.get('.goal').text()).toBe('提案')
  })

  it('默认只展示阵容，确认时发送一次零字节请求并进入同一房间', async () => {
    const pending = deferredResponse()
    const baseFetch = fetchMock.getMockImplementation()!
    let current = draft
    fetchMock.mockImplementation((path, init) => {
      if (path === startPath) return pending.promise
      if (path === `/api/v1/discussions/${draft.id}`) return ok(current)
      return baseFetch(path, init)
    })
    const { wrapper, router } = await open(rosterPath)
    await flushPromises()
    expect(posts()).toHaveLength(0)
    expect(wrapper.get('.goal').text()).toBe('评估')
    expect(wrapper.get('.participant-card--host').attributes('data-color')).toBe('violet')
    expect(wrapper.get('.participant-card--host').text()).not.toContain('初始立场')
    expect(wrapper.findAll('.expert-grid .participant-card')).toHaveLength(4)
    for (const expert of draft.participants.slice(1)) {
      expect(wrapper.text()).toContain(expert.profession)
      expect(wrapper.text()).toContain(expert.title)
      expect(wrapper.text()).toContain(expert.stance)
    }
    expect(wrapper.findAll('.expert-grid .perspective')).toHaveLength(4)
    await wrapper.get('.confirmation').trigger('submit')
    await wrapper.get('.confirmation').trigger('submit')
    expect(posts().map(([path]) => path)).toEqual([startPath])
    expect(posts()[0][1].body).toBeUndefined()
    expect(posts()[0][1].headers).toEqual({ Accept: 'application/json' })
    current = { ...draft, status: 'RUNNING', lastEventSeq: 1 } as typeof draft
    pending.resolve(fromFixture(startAccepted))
    await vi.waitFor(() => expect(wrapper.text()).toContain('讨论中'))
    expect(router.currentRoute.value.path).toBe(`/discussions/${draft.id}`)
    expect(wrapper.text()).toContain('讨论中')
    expect(wrapper.find('.confirmation').exists()).toBe(false)
  })

  it('容量不足时留在阵容页，并可再次确认', async () => {
    const baseFetch = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((path, init) => path === startPath
      ? fromFixture(fixture.http[19].response)
      : baseFetch(path, init))
    const { wrapper, router } = await open(rosterPath)
    await flushPromises()
    await wrapper.get('.confirmation').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe(rosterPath)
    expect(wrapper.get('[role="alert"]').text()).toContain('暂时满员')
    expect(wrapper.get('.confirmation button').attributes('aria-disabled')).toBe('false')
    await wrapper.get('.confirmation').trigger('submit')
    await flushPromises()
    expect(posts().map(([path]) => path)).toEqual([startPath, startPath])
  })

  it('旁观进入原房间，只查询列表和快照', async () => {
    const baseFetch = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((path, init) => {
      if (path === '/api/v1/discussions?status=ACTIVE') return fromFixture(activeRooms)
      if (path === `/api/v1/discussions/${fixture.rooms.B}`) return ok(fixture.snapshots.B2)
      return baseFetch(path, init)
    })
    const { wrapper, router } = await open()
    await flushPromises()
    await wrapper.findAll('.room-link')[1].trigger('click')
    await vi.waitFor(() => expect(wrapper.get('h1').text()).toBe(fixture.snapshots.B2.topic))
    expect(router.currentRoute.value.path).toBe(`/discussions/${fixture.rooms.B}`)
    expect(wrapper.get('h1').text()).toBe(fixture.snapshots.B2.topic)
    expect(wrapper.find('.confirmation').exists()).toBe(false)
    expect(posts()).toHaveLength(0)
  })

  it('离开大厅后取消创建请求并丢弃迟到草稿', async () => {
    const pending = deferredResponse()
    const baseFetch = fetchMock.getMockImplementation()!
    fetchMock.mockImplementation((path, init) => path === '/api/v1/discussions' ? pending.promise : baseFetch(path, init))
    const { wrapper, router } = await open()
    await wrapper.get('textarea').setValue('离开大厅后的请求')
    await wrapper.get('.new-discussion form').trigger('submit')
    await router.push('/other')
    await flushPromises()
    expect(posts()[0][1].signal?.aborted).toBe(true)
    pending.resolve(fromFixture(fixture.http[4].response))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/other')
    expect(wrapper.get('h1').text()).toBe('页面未找到')
  })

  it('切换房间后丢弃旧快照，离开阵容后忽略迟到确认', async () => {
    const snapshot = deferredResponse()
    const confirmation = deferredResponse()
    const baseFetch = fetchMock.getMockImplementation()!
    let firstA = true
    fetchMock.mockImplementation((path, init) => {
      if (path === `/api/v1/discussions/${fixture.rooms.B}`) return ok(fixture.snapshots.B2)
      if (path === `/api/v1/discussions/${draft.id}` && firstA) {
        firstA = false
        return snapshot.promise
      }
      if (path === startPath) return confirmation.promise
      return baseFetch(path, init)
    })
    const { wrapper, router } = await open(rosterPath)
    await router.push(`/discussions/${fixture.rooms.B}`)
    await flushPromises()
    expect(fetchMock.mock.calls[0][1].signal?.aborted).toBe(true)
    snapshot.resolve(await ok(draft))
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe(fixture.snapshots.B2.topic)

    await router.push(rosterPath)
    await flushPromises()
    await wrapper.get('.confirmation').trigger('submit')
    await router.push('/')
    await flushPromises()
    expect(posts()[0][1].signal?.aborted).toBe(true)
    confirmation.resolve(fromFixture(startAccepted))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
  })
})
