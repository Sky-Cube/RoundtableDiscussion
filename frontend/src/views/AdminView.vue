<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import {
  admin,
  discussions,
  goals,
  requestError,
  stageLabels,
  statusLabels,
} from '../api/discussions'
import type {
  AdminDiscussionRow,
  AdminEvent,
  AdminOverview,
  AdminRoom,
  DiscussionStatus,
  Preset,
} from '../api/discussions'
import { defaultExpertCount, expertCounts, maxTopicLength, normalizeTopic, validateTopic } from '../topic'
import './admin.css'

/* ---------------- 数据加载 ---------------- */

type Filter = 'ALL' | DiscussionStatus
const filters: { value: Filter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'DRAFT', label: '待确认' },
  { value: 'RUNNING', label: '讨论中' },
  { value: 'SUMMARIZING', label: '正在总结' },
  { value: 'COMPLETED', label: '已结束' },
  { value: 'FAILED', label: '已中断' },
]

const filter = ref<Filter>('ALL')
const keyword = ref('')
const dateInput = ref('')
const appliedKeyword = ref('')
const appliedDate = ref('')
const overview = ref<AdminOverview>()
const rooms = ref<AdminDiscussionRow[]>([])
const presets = ref<Preset[]>([])
const loading = ref(true)
const loadError = ref('')
const listError = ref('')
const connection = computed<'loading' | 'connected' | 'failed'>(() => {
  if (loading.value) return 'loading'
  return loadError.value ? 'failed' : 'connected'
})
const connectionLabel = computed(() => {
  if (connection.value === 'connected') return '已连接'
  return connection.value === 'failed' ? '连接失败' : '连接中'
})

let listController: AbortController | undefined

async function loadOverview() {
  const controller = new AbortController()
  try {
    overview.value = await admin.overview(controller.signal)
  } catch (error) {
    loadError.value = requestError(error, '暂时无法读取后台总览，请重试。')
  }
}

async function loadRooms() {
  listController?.abort()
  const controller = new AbortController()
  listController = controller
  try {
    rooms.value = await admin.discussions(filter.value, appliedKeyword.value, appliedDate.value, controller.signal)
    listError.value = ''
  } catch (error) {
    if (!controller.signal.aborted) {
      rooms.value = []
      listError.value = requestError(error, '暂时无法读取房间列表，请重试。')
    }
  }
}

async function loadPresets() {
  const controller = new AbortController()
  try {
    presets.value = await discussions.listPresets(controller.signal)
  } catch {
    presets.value = []
  }
}

async function reload() {
  loading.value = true
  await Promise.all([loadOverview(), loadRooms(), loadPresets()])
  loading.value = false
}

async function changeFilter(next: Filter) {
  if (filter.value === next) return
  filter.value = next
  await loadRooms()
}

async function applySearch() {
  appliedKeyword.value = keyword.value.trim()
  appliedDate.value = dateInput.value.trim()
  await loadRooms()
}

async function clearSearch() {
  keyword.value = ''
  dateInput.value = ''
  appliedKeyword.value = ''
  appliedDate.value = ''
  await loadRooms()
}

/* ---------------- 房间详情 ---------------- */

type DetailTab = 'members' | 'transcript' | 'insights' | 'events'
const detailTabs: { value: DetailTab; label: string }[] = [
  { value: 'members', label: '成员' },
  { value: 'transcript', label: '实录' },
  { value: 'insights', label: '观点' },
  { value: 'events', label: '事件' },
]

const selectedId = ref('')
const detail = ref<AdminRoom>()
const detailTab = ref<DetailTab>('transcript')
const detailLoading = ref(false)
const detailError = ref('')
const starting = ref('')
let detailController: AbortController | undefined

async function toggleRow(row: AdminDiscussionRow) {
  if (selectedId.value === row.id) {
    closeDetail()
    return
  }
  detailController?.abort()
  selectedId.value = row.id
  detail.value = undefined
  detailTab.value = 'transcript'
  await loadDetail(row.id)
}

function closeDetail() {
  detailController?.abort()
  detailController = undefined
  selectedId.value = ''
  detail.value = undefined
  detailError.value = ''
}

async function loadDetail(id: string) {
  const controller = new AbortController()
  detailController = controller
  detailLoading.value = true
  detailError.value = ''
  try {
    const room = await admin.room(id, controller.signal)
    if (controller.signal.aborted) return
    detail.value = room
  } catch (error) {
    if (!controller.signal.aborted) {
      detailError.value = requestError(error, '暂时无法读取房间详情，请重试。')
    }
  } finally {
    if (!controller.signal.aborted) {
      detailLoading.value = false
    }
  }
}

async function startRoom(row: AdminDiscussionRow) {
  if (starting.value) return
  starting.value = row.id
  const controller = new AbortController()
  try {
    await discussions.start(row.id, controller.signal)
    await Promise.all([loadOverview(), loadRooms()])
    if (selectedId.value === row.id) await loadDetail(row.id)
    ensurePolling()
  } catch (error) {
    loadError.value = requestError(error, '暂时无法开始这场讨论，请重试。')
  } finally {
    starting.value = ''
  }
}

/* ---------------- 轮询：进行中的房间自动刷新 ---------------- */

const live = computed(() => rooms.value.some(room => room.status === 'RUNNING' || room.status === 'SUMMARIZING'))
const detailLive = computed(() => {
  const status = detail.value?.snapshot.status
  return status === 'RUNNING' || status === 'SUMMARIZING'
})
let polling: number | undefined
let pollingBusy = false

function ensurePolling() {
  if (polling !== undefined) return
  polling = window.setInterval(async () => {
    if (pollingBusy || (!live.value && !detailLive.value)) return
    pollingBusy = true
    try {
      await loadRooms()
      await loadOverview()
      if (selectedId.value && detailLive.value) {
        await loadDetail(selectedId.value)
      }
    } finally {
      pollingBusy = false
    }
  }, 2000)
}

function stopPolling() {
  if (polling !== undefined) {
    window.clearInterval(polling)
    polling = undefined
  }
}

/* ---------------- 新建草稿 / 复制预设 ---------------- */

const topic = ref('')
const topicInput = ref<HTMLTextAreaElement>()
const expertCount = ref<number>(defaultExpertCount)
const normalizedTopic = computed(() => normalizeTopic(topic.value))
const topicLength = computed(() => [...normalizedTopic.value.trim()].length)
const topicError = ref('')
const creating = ref(false)
const draftError = ref('')
const draft = ref<AdminRoom['snapshot']>()
const draftSource = ref('')
let draftController: AbortController | undefined

function submitDraft() {
  if (creating.value) return
  topicError.value = validateTopic(normalizedTopic.value)
  draftError.value = ''
  if (topicError.value) {
    topicInput.value?.focus()
    return
  }
  void createDraft('新建草稿', signal => discussions.create(normalizedTopic.value.trim(), expertCount.value, signal))
}

function copyPreset(preset: Preset) {
  if (creating.value) return
  draftError.value = ''
  void createDraft(`复制自预设 ${preset.slug}`, signal => discussions.copyPreset(preset.slug, signal))
}

async function createDraft(source: string, request: (signal: AbortSignal) => Promise<AdminRoom['snapshot']>) {
  if (creating.value) return
  const controller = new AbortController()
  draftController = controller
  creating.value = true
  draft.value = undefined
  try {
    const result = await request(controller.signal)
    if (controller.signal.aborted) return
    if (result.status !== 'DRAFT') throw new Error('接口未返回草稿快照')
    draft.value = result
    draftSource.value = source
    await Promise.all([loadOverview(), loadRooms()])
  } catch (error) {
    if (!controller.signal.aborted) {
      draftError.value = requestError(error, '暂时无法生成草稿，请重试。')
    }
  } finally {
    if (!controller.signal.aborted) {
      creating.value = false
    }
  }
}

/* ---------------- 展示辅助 ---------------- */

const timeFormat = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
})

function formatTime(value: string | null | undefined) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : timeFormat.format(date)
}

const eventLabels: Record<string, string> = {
  'discussion.status': '房间状态',
  'participant.status': '席位状态',
  'utterance.added': '新增发言',
  'insight.updated': '观点更新',
}

const kindLabels: Record<string, string> = {
  OPENING: '开场',
  EXPERT: '专家',
  FOLLOW_UP: '追问',
  BRIDGE: '主持追问',
  SUMMARY: '收尾',
}

const participantNames = computed(() => {
  const map = new Map<string, string>()
  for (const member of detail.value?.snapshot.participants ?? []) {
    map.set(member.id, member.displayName)
  }
  return map
})

function speakerOf(participantId: string) {
  return participantNames.value.get(participantId) ?? participantId
}

function eventSeqOf(event: AdminEvent) {
  return event.seq
}

const statCards = computed(() => {
  const counts = overview.value?.discussionCounts
  return [
    { label: '房间总数', value: overview.value?.totalDiscussions ?? 0 },
    { label: '待确认', value: counts?.DRAFT ?? 0 },
    { label: '讨论中', value: (counts?.RUNNING ?? 0) + (counts?.SUMMARIZING ?? 0) },
    { label: '已结束', value: counts?.COMPLETED ?? 0 },
    { label: '发言', value: overview.value?.utteranceCount ?? 0 },
    { label: '结论', value: overview.value?.insightCount ?? 0 },
    { label: '事件', value: overview.value?.eventCount ?? 0 },
  ]
})

onMounted(async () => {
  await reload()
  ensurePolling()
})

onBeforeUnmount(() => {
  stopPolling()
  listController?.abort()
  detailController?.abort()
  draftController?.abort()
})
</script>

<template>
  <div class="admin">
    <header class="admin-header">
      <div class="admin-header__title">
        <p class="admin-kicker">ADMIN CONSOLE / ROUNDTABLE</p>
        <h1>AI Panel Studio · 后台管理</h1>
        <p class="admin-subtitle">查看全部房间、辩论实录、实时观点与事件流，并直接确认开场。</p>
      </div>
      <div class="admin-connection" :data-state="connection" role="status">
        <span class="admin-connection__dot" aria-hidden="true"></span>
        <span>后端 {{ connectionLabel }}</span>
      </div>
    </header>

    <p v-if="connection === 'failed'" class="admin-alert" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" class="admin-button admin-button--ghost" @click="reload">重试</button>
    </p>

    <ul class="admin-stats" aria-label="后台统计">
      <li v-for="stat in statCards" :key="stat.label" class="admin-stat">
        <span class="admin-stat__value">{{ stat.value }}</span>
        <span class="admin-stat__label">{{ stat.label }}</span>
      </li>
    </ul>

    <section v-if="draft" class="admin-card admin-result" aria-labelledby="admin-result-title" role="status">
      <header class="admin-card__header">
        <div>
          <p class="admin-kicker">LAST OPERATION</p>
          <h2 id="admin-result-title">已创建草稿房间</h2>
        </div>
        <span class="admin-chip" data-status="DRAFT">{{ statusLabels.DRAFT }}</span>
      </header>
      <dl class="admin-facts">
        <div><dt>来源</dt><dd>{{ draftSource }}</dd></div>
        <div><dt>议题</dt><dd>{{ draft.topic }}</dd></div>
        <div><dt>专家人数</dt><dd>{{ draft.expertCount }} 位</dd></div>
        <div><dt>房间 ID</dt><dd class="admin-mono">{{ draft.id }}</dd></div>
      </dl>
      <RouterLink class="admin-button admin-button--primary" :to="{ name: 'discussion', params: { id: draft.id } }">
        打开房间
      </RouterLink>
    </section>

    <section class="admin-card" aria-labelledby="admin-rooms-title">
      <header class="admin-card__header">
        <div>
          <h2 id="admin-rooms-title">辩论房间</h2>
          <p class="admin-card__hint">全部状态的房间与各表记录数；待确认房间可直接开始讨论。</p>
        </div>
        <button type="button" class="admin-button admin-button--ghost" :disabled="loading" @click="reload">刷新</button>
      </header>

      <div class="admin-filters" role="group" aria-label="按状态筛选">
        <button
          v-for="item in filters"
          :key="item.value"
          type="button"
          class="admin-filter"
          :aria-pressed="filter === item.value"
          @click="changeFilter(item.value)"
        >{{ item.label }}</button>
      </div>

      <form class="admin-search" role="search" @submit.prevent="applySearch">
        <label class="admin-search__field">
          <span class="sr-only">按议题名称搜索</span>
          <input v-model="keyword" type="search" maxlength="60" placeholder="按议题名称搜索" />
        </label>
        <label class="admin-search__field admin-search__field--date">
          <span class="sr-only">按日期搜索</span>
          <input v-model="dateInput" type="date" />
        </label>
        <button type="submit" class="admin-button admin-button--primary admin-button--small" :disabled="loading">搜索</button>
        <button type="button" class="admin-button admin-button--ghost admin-button--small" @click="clearSearch">清空</button>
        <span class="admin-search__hint" role="status">
          {{ rooms.length }} 个结果<template v-if="appliedKeyword"> · 关键词「{{ appliedKeyword }}」</template><template v-if="appliedDate"> · 日期 {{ appliedDate }}</template>
        </span>
      </form>

      <p v-if="listError" class="admin-alert" role="alert">
        <span>{{ listError }}</span>
        <button type="button" class="admin-button admin-button--ghost" @click="loadRooms">重试</button>
      </p>

      <p v-else-if="loading && !rooms.length" class="admin-muted" role="status">正在读取房间…</p>
      <p v-else-if="!rooms.length" class="admin-empty">
        {{ appliedKeyword || appliedDate ? '没有匹配的房间，试试更换关键词或日期。' : '该筛选下还没有房间。' }}
      </p>

      <div v-else class="admin-table-wrap">
        <table class="admin-table">
          <caption class="sr-only">辩论房间列表</caption>
          <thead>
            <tr>
              <th scope="col">议题</th>
              <th scope="col">状态</th>
              <th scope="col">目标</th>
              <th scope="col">专家</th>
              <th scope="col">发言</th>
              <th scope="col">结论</th>
              <th scope="col">事件</th>
              <th scope="col">开始时间</th>
              <th scope="col"><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="room in rooms"
              :key="room.id"
              :class="{ 'is-selected': selectedId === room.id }"
              @click="toggleRow(room)"
            >
              <td class="admin-table__topic">{{ room.topic }}</td>
              <td><span class="admin-chip" :data-status="room.status">{{ statusLabels[room.status] }}</span></td>
              <td>{{ goals[room.discussionGoal] }}</td>
              <td class="admin-table__num">{{ room.expertCount }} 位</td>
              <td class="admin-table__num">{{ room.utteranceCount }}</td>
              <td class="admin-table__num">{{ room.insightCount }}</td>
              <td class="admin-table__num">{{ room.eventCount }}</td>
              <td class="admin-table__time">{{ formatTime(room.startedAt ?? room.createdAt) }}</td>
              <td class="admin-table__action">
                <button
                  v-if="room.status === 'DRAFT'"
                  type="button"
                  class="admin-button admin-button--primary admin-button--small"
                  :disabled="starting === room.id"
                  @click.stop="startRoom(room)"
                >{{ starting === room.id ? '正在开始…' : '开始讨论' }}</button>
                <button
                  v-else
                  type="button"
                  class="admin-button admin-button--ghost admin-button--small"
                  :aria-expanded="selectedId === room.id"
                  @click.stop="toggleRow(room)"
                >{{ selectedId === room.id ? '收起' : '查看' }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="selectedId" id="admin-room-detail" class="admin-detail">
        <p v-if="detailLoading && !detail" class="admin-muted" role="status">正在读取房间详情…</p>
        <p v-else-if="detailError" class="admin-alert" role="alert">
          <span>{{ detailError }}</span>
          <button type="button" class="admin-button admin-button--ghost" @click="loadDetail(selectedId)">重试</button>
        </p>

        <template v-else-if="detail">
          <header class="admin-detail__header">
            <div>
              <p class="admin-kicker">ROOM DETAIL</p>
              <h3>{{ detail.snapshot.topic }}</h3>
            </div>
            <div class="admin-detail__meta">
              <span class="admin-chip" :data-status="detail.snapshot.status">{{ statusLabels[detail.snapshot.status] }}</span>
              <span class="admin-tag">{{ goals[detail.snapshot.discussionGoal] }}</span>
              <span v-if="detailLive" class="admin-tag admin-tag--live">实时刷新</span>
            </div>
          </header>

          <dl class="admin-facts">
            <div><dt>房间 ID</dt><dd class="admin-mono">{{ detail.snapshot.id }}</dd></div>
            <div><dt>开始</dt><dd>{{ formatTime(detail.snapshot.startedAt) }}</dd></div>
            <div><dt>结束</dt><dd>{{ formatTime(detail.snapshot.endedAt) }}</dd></div>
            <div><dt>事件游标</dt><dd>{{ detail.snapshot.lastEventSeq }} · 共 {{ detail.events.length }} 条</dd></div>
            <div><dt>观点状态</dt><dd>{{ detail.snapshot.insightStatus }} · 至发言 #{{ detail.snapshot.insightsThroughUtteranceSeq }}</dd></div>
            <div v-if="detail.snapshot.errorCode"><dt>错误码</dt><dd class="admin-mono">{{ detail.snapshot.errorCode }}</dd></div>
          </dl>

          <p v-if="detail.snapshot.summary" class="admin-summary" role="note">
            <strong>主持收尾</strong>{{ detail.snapshot.summary }}
          </p>

          <div class="admin-tabs" role="tablist" aria-label="房间详情">
            <button
              v-for="tab in detailTabs"
              :key="tab.value"
              type="button"
              role="tab"
              class="admin-tab"
              :aria-selected="detailTab === tab.value"
              @click="detailTab = tab.value"
            >{{ tab.label }}</button>
          </div>

          <div v-if="detailTab === 'members'" class="admin-panel">
            <ul class="admin-participants">
              <li v-for="member in detail.snapshot.participants" :key="member.id" class="admin-participant">
                <span class="admin-participant__role">{{ member.role === 'HOST' ? '主持' : '专家' }}</span>
                <strong class="admin-participant__name">{{ member.displayName }}</strong>
                <span class="admin-participant__meta">{{ member.profession }} · {{ member.title }}</span>
                <span class="admin-chip" :data-status="member.stageStatus">{{ stageLabels[member.stageStatus] }}</span>
                <span v-if="member.role === 'EXPERT'" class="admin-participant__stance">{{ member.stance }}</span>
                <span v-if="member.publicFocus" class="admin-participant__focus">{{ member.publicFocus }}</span>
              </li>
            </ul>
          </div>

          <div v-else-if="detailTab === 'transcript'" class="admin-panel">
            <p v-if="!detail.snapshot.transcript.length" class="admin-empty">这场讨论还没有公开发言。</p>
            <ol v-else class="admin-lines">
              <li v-for="line in detail.snapshot.transcript" :key="line.seq" class="admin-line">
                <span class="admin-line__seq">#{{ line.seq }}</span>
                <span class="admin-line__kind">{{ kindLabels[line.kind] ?? line.kind }}</span>
                <strong class="admin-line__speaker">{{ speakerOf(line.participantId) }}</strong>
                <span class="admin-line__text">{{ line.content }}</span>
                <span v-if="line.replyToUtteranceSeq" class="admin-line__reply">回应 #{{ line.replyToUtteranceSeq }}</span>
              </li>
            </ol>
          </div>

          <div v-else-if="detailTab === 'insights'" class="admin-panel">
            <p v-if="!detail.snapshot.insights.length" class="admin-empty">暂无可公开的共识或分歧。</p>
            <ul v-else class="admin-insights">
              <li v-for="insight in detail.snapshot.insights" :key="insight.id" class="admin-insight">
                <span class="admin-chip" :data-kind="insight.kind">
                  {{ insight.kind === 'CONSENSUS' ? '共识' : '分歧' }}
                </span>
                <span class="admin-insight__text">{{ insight.text }}</span>
                <span class="admin-insight__evidence">
                  证据
                  <span v-for="seq in insight.evidenceUtteranceSeqs" :key="seq" class="admin-mono">#{{ seq }}</span>
                </span>
              </li>
            </ul>
          </div>

          <div v-else class="admin-panel">
            <ol class="admin-events">
              <li v-for="event in detail.events" :key="event.seq" class="admin-event">
                <span class="admin-event__seq">#{{ eventSeqOf(event) }}</span>
                <span class="admin-event__type">{{ eventLabels[event.type] ?? event.type }}</span>
                <span class="admin-event__time">{{ formatTime(event.createdAt) }}</span>
              </li>
            </ol>
          </div>

          <RouterLink class="admin-link" :to="{ name: 'discussion', params: { id: detail.snapshot.id } }">打开房间</RouterLink>
        </template>
      </div>
    </section>

    <section class="admin-card" aria-labelledby="admin-create-title">
      <header class="admin-card__header">
        <div>
          <h2 id="admin-create-title">新建草稿</h2>
          <p class="admin-card__hint">生成待确认的 DRAFT 房间，然后在房间列表里直接开始讨论。</p>
        </div>
      </header>

      <form class="admin-form" novalidate @submit.prevent="submitDraft">
        <div class="admin-field">
          <div class="admin-field__label">
            <label for="admin-topic">议题</label>
            <span :class="{ 'is-invalid': topicLength > maxTopicLength }">{{ topicLength }} / {{ maxTopicLength }}</span>
          </div>
          <textarea
            id="admin-topic"
            ref="topicInput"
            v-model="topic"
            rows="3"
            placeholder="例如：中小团队应优先自研还是采购 AI 客服？"
            aria-required="true"
            :aria-invalid="!!topicError"
            :aria-describedby="topicError ? 'admin-topic-error' : undefined"
            @input="topicError = ''"
          ></textarea>
          <p v-if="topicError" id="admin-topic-error" class="admin-field__error" role="alert">{{ topicError }}</p>
        </div>

        <div class="admin-form__actions">
          <div class="admin-field admin-field--inline">
            <label for="admin-expert-count">专家人数</label>
            <select id="admin-expert-count" v-model="expertCount">
              <option v-for="count in expertCounts" :key="count" :value="count">{{ count }} 位</option>
            </select>
          </div>
          <button type="submit" class="admin-button admin-button--primary" :disabled="creating">
            {{ creating ? '正在生成阵容…' : '生成阵容' }}
          </button>
        </div>
      </form>

      <p v-if="draftError" class="admin-alert" role="alert">{{ draftError }}</p>
    </section>

    <section class="admin-card" aria-labelledby="admin-presets-title">
      <header class="admin-card__header">
        <div>
          <h2 id="admin-presets-title">预设议题</h2>
          <p class="admin-card__hint">来自后端预设，复制后得到新的草稿房间。</p>
        </div>
      </header>

      <p v-if="!presets.length" class="admin-empty">当前没有可用的预设议题。</p>

      <div v-else class="admin-table-wrap">
        <table class="admin-table admin-preset-table">
          <caption class="sr-only">预设议题列表</caption>
          <thead>
            <tr>
              <th scope="col">标识</th>
              <th scope="col">议题</th>
              <th scope="col">讨论目标</th>
              <th scope="col">专家人数</th>
              <th scope="col"><span class="sr-only">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="preset in presets" :key="preset.slug">
              <td class="admin-mono">{{ preset.slug }}</td>
              <td class="admin-table__topic">{{ preset.topic }}</td>
              <td>{{ goals[preset.discussionGoal] }}</td>
              <td class="admin-table__num">{{ preset.expertCount }} 位</td>
              <td class="admin-table__action">
                <button
                  type="button"
                  class="admin-button admin-button--ghost"
                  :disabled="creating"
                  :aria-label="`复制预设「${preset.topic}」为草稿`"
                  @click="copyPreset(preset)"
                >复制为草稿</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <p class="sr-only" role="status">{{ creating ? '正在创建草稿' : draft ? '草稿已创建' : '' }}</p>
  </div>
</template>
