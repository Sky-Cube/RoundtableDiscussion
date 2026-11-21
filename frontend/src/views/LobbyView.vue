<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { discussions, goals, requestError, statusLabels } from '../api/discussions'
import type { DiscussionSnapshot } from '../api/discussions'
import { useLoad } from '../composables/useLoad'
import { defaultExpertCount, expertCounts, maxTopicLength, normalizeTopic, validateTopic } from '../topic'
import { seatColor } from '../components/studio/avatar-style'

const router = useRouter()
const rooms = useLoad(discussions.listActive, '暂时无法读取进行中的讨论，请重试。')
const presets = useLoad(discussions.listPresets, '暂时无法读取示例议题，请重试。')
const topic = ref('')
const topicInput = ref<HTMLTextAreaElement>()
const expertCount = ref<number>(defaultExpertCount)
const normalizedTopic = computed(() => normalizeTopic(topic.value))
const topicLength = computed(() => [...normalizedTopic.value.trim()].length)
const topicError = ref('')
const creationError = ref<{ source: string | null; message: string }>()
const creation = ref<{ source: string | null }>()
let pending: AbortController | undefined
let roomRefresh: number | undefined

const heroSeats = [
  { initial: '主', color: seatColor('violet') },
  { initial: '成', color: seatColor('blue') },
  { initial: '研', color: seatColor('green') },
  { initial: '创', color: seatColor('amber') },
  { initial: '用', color: seatColor('teal') },
]

onMounted(() => {
  window.addEventListener('focus', rooms.load)
  roomRefresh = window.setInterval(() => {
    if (document.visibilityState === 'visible') void rooms.load()
  }, 5_000)
})
onBeforeUnmount(() => {
  window.removeEventListener('focus', rooms.load)
  window.clearInterval(roomRefresh)
  pending?.abort()
})

function create() {
  if (pending) return
  topicError.value = validateTopic(normalizedTopic.value)
  if (topicError.value) {
    topicInput.value?.focus()
    return
  }
  const submittedTopic = normalizedTopic.value.trim()
  const submittedCount = expertCount.value
  void openRoster(null, signal => discussions.create(submittedTopic, submittedCount, signal))
}

async function openRoster(source: string | null, request: (signal: AbortSignal) => Promise<DiscussionSnapshot>) {
  if (pending) return
  const controller = new AbortController()
  pending = controller
  creation.value = { source }
  creationError.value = undefined
  try {
    const draft = await request(controller.signal)
    if (controller.signal.aborted) return
    if (draft.status !== 'DRAFT') throw new Error('创建响应未返回草稿')
    await router.push({ name: 'roster', params: { id: draft.id } })
  } catch (error) {
    if (!controller.signal.aborted) {
      creationError.value = { source, message: requestError(error, '暂时无法创建讨论，请重试。') }
    }
  } finally {
    if (!controller.signal.aborted) {
      pending = undefined
      creation.value = undefined
    }
  }
}

function copyPreset(slug: string) {
  void openRoster(slug, signal => discussions.copyPreset(slug, signal))
}
</script>

<template>
  <div class="page lobby">
    <section class="lobby-hero" aria-labelledby="new-discussion-title">
      <div class="hero-copy">
        <span class="eyebrow">AI PANEL STUDIO / ROUNDTABLE 01</span>
        <h1>把分歧<br /><em>放上桌面</em></h1>
        <p>输入一个值得追问的议题，让 AI 主持人组建阵容，让不同职业、不同立场的专家在圆桌上交换证据、补充与反驳。</p>
        <div class="hero-meta" aria-label="演播厅能力">
          <span class="hero-live">实时圆桌</span>
          <span><i aria-hidden="true"></i>1 位主持 + 2–6 位专家</span>
          <span><i aria-hidden="true"></i>自适应席位与发言调度</span>
        </div>
      </div>

      <div class="hero-stage" aria-hidden="true">
        <div class="hero-orbit">
          <span class="hero-orbit__ring"></span>
          <span class="hero-orbit__table"></span>
          <span
            v-for="(seat, index) in heroSeats"
            :key="index"
            class="hero-seat"
            :style="{ '--seat': index + 1, '--seat-color': seat.color }"
            :data-initial="seat.initial"
          ></span>
        </div>
        <div class="hero-stage__meta">
          <span><strong>STUDIO B</strong></span>
          <i></i>
          <span>CAM 01 · 35MM</span>
          <i></i>
          <span>LIVE COMPOSITE</span>
        </div>
      </div>
    </section>

    <div class="lobby-grid">
      <section class="panel new-discussion" aria-labelledby="new-discussion-title">
        <header class="form-heading">
          <div>
            <span class="eyebrow">ROUND CONFIGURATION</span>
            <h2 id="new-discussion-title">发起圆桌讨论</h2>
            <p>先写下议题，再预览嘉宾阵容。确认后，讨论才会正式开始。</p>
          </div>
          <span class="form-heading__badge" aria-hidden="true">01</span>
        </header>

        <form novalidate @submit.prevent="create">
          <div class="field">
            <div class="field__label">
              <label for="topic">讨论议题</label>
              <span id="topic-length" :class="{ invalid: topicLength > maxTopicLength }">
                {{ topicLength }} / {{ maxTopicLength }}
              </span>
            </div>
            <textarea
              id="topic"
              ref="topicInput"
              v-model="topic"
              rows="4"
              placeholder="例如：中小团队应优先自研还是采购 AI 客服？"
              aria-required="true"
              :aria-invalid="!!topicError"
              :aria-describedby="topicError ? 'topic-length topic-error' : 'topic-length'"
              @input="topicError = ''"
            ></textarea>
            <p v-if="topicError" id="topic-error" class="invalid field-error" role="alert">{{ topicError }}</p>
          </div>
          <div class="form-actions">
            <div class="field count-field">
              <label for="expert-count">专家人数</label>
              <select id="expert-count" v-model="expertCount">
                <option v-for="count in expertCounts" :key="count" :value="count">{{ count }} 位</option>
              </select>
            </div>
            <button class="button primary" type="submit" :aria-disabled="!!creation">
              {{ creation?.source === null ? '正在生成阵容…' : '生成专家阵容' }}
            </button>
          </div>
        </form>
        <p class="simulation-note">嘉宾身份、头像与发言均由 AI 模拟，仅用于演播演示。</p>
        <p v-if="creationError?.source === null" role="alert" class="notice notice--error">{{ creationError.message }}</p>
        <p class="sr-only" role="status">{{ creation ? '正在准备阵容' : '' }}</p>
      </section>

      <section class="panel active-discussions" aria-labelledby="active-title">
        <header class="section-header">
          <div>
            <span class="eyebrow">ON THE FLOOR</span>
            <h2 id="active-title">进行中的讨论</h2>
          </div>
          <button
            v-if="rooms.data.value && !rooms.error.value"
            type="button"
            class="text-button"
            :aria-disabled="rooms.loading.value"
            @click="rooms.load"
          >刷新</button>
        </header>
        <p v-if="rooms.loading.value && !rooms.data.value" class="muted" role="status">正在加载房间…</p>
        <div v-if="rooms.error.value" class="notice notice--error">
          <p role="alert">{{ rooms.error.value }}</p>
          <button type="button" class="text-button" @click="rooms.load">重试加载房间</button>
        </div>
        <div v-else-if="rooms.data.value?.length === 0" class="empty-rooms">
          <p>暂时没有进行中的讨论</p>
          <a href="#topic" class="text-button" @click.prevent="topicInput?.focus()">发起一场讨论</a>
        </div>
        <ul v-if="rooms.data.value?.length" class="room-list">
          <li v-for="room in rooms.data.value" :key="room.id">
            <RouterLink class="room-link" :to="{ name: 'discussion', params: { id: room.id } }">
              <span class="room-link__body">
                <span class="status" :data-status="room.status">{{ statusLabels[room.status] }}</span>
                <strong>{{ room.topic }}</strong>
                <span class="muted">{{ room.expertCount }} 位专家 · 自由发言中</span>
              </span>
              <span class="room-link__enter">旁观 <span aria-hidden="true">↗</span></span>
            </RouterLink>
          </li>
        </ul>
      </section>
    </div>

    <section class="preset-section" aria-labelledby="presets-title">
      <header class="section-header">
        <div>
          <span class="eyebrow">CURATED TOPICS</span>
          <h2 id="presets-title">从示例议题开始</h2>
        </div>
      </header>
      <p v-if="presets.loading.value" class="muted" role="status">正在加载示例…</p>
      <div v-else-if="presets.error.value" class="notice notice--error">
        <p role="alert">{{ presets.error.value }}</p>
        <button type="button" class="text-button" @click="presets.load">重试加载示例</button>
      </div>
      <p v-else-if="!presets.data.value?.length" class="muted">暂时没有示例议题。</p>
      <ul v-else class="preset-list">
        <li v-for="preset in presets.data.value" :key="preset.slug">
          <button
            type="button"
            class="preset-button"
            :aria-disabled="!!creation"
            @click="copyPreset(preset.slug)"
          >
            <span class="preset-meta">{{ goals[preset.discussionGoal] }} · {{ preset.expertCount }} 位专家</span>
            <strong>{{ preset.topic }}</strong>
            <span class="preset-action">{{ creation?.source === preset.slug ? '正在准备阵容…' : '查看阵容 →' }}</span>
          </button>
          <p v-if="creationError?.source === preset.slug" role="alert" class="notice notice--error">
            {{ creationError.message }}
          </p>
        </li>
      </ul>
    </section>
  </div>
</template>
