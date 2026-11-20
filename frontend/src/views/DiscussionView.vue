<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import ParticipantCard from '../components/ParticipantCard.vue'
import { ApiError, discussions, goals, requestError, statusLabels } from '../api/discussions'
import { useLoad } from '../composables/useLoad'

const router = useRouter()
const roomId = String(useRoute().params.id)
const { data: room, loading, error, load } = useLoad(async signal => {
  const snapshot = await discussions.get(roomId, signal)
  if (snapshot.id !== roomId) throw new Error('快照与当前房间不匹配')
  return snapshot
}, '暂时无法读取这场讨论，请重试。')

const host = computed(() => room.value?.participants.find(participant => participant.role === 'HOST'))
const experts = computed(() => room.value?.participants.filter(participant => participant.role === 'EXPERT') ?? [])
const starting = ref(false)
const startError = ref('')
const startConflict = ref(false)
const canStart = computed(() => room.value?.status === 'DRAFT' && !startConflict.value)
let pending: AbortController | undefined

onBeforeUnmount(() => pending?.abort())

async function start() {
  if (pending || !canStart.value) return
  const controller = new AbortController()
  pending = controller
  starting.value = true
  startError.value = ''
  try {
    const result = await discussions.start(roomId, controller.signal)
    if (controller.signal.aborted) return
    if (result.id !== roomId || (result.status !== 'RUNNING' && result.status !== 'SUMMARIZING')) {
      throw new Error('确认响应与当前房间不匹配')
    }
    await router.replace({ name: 'discussion', params: { id: roomId } })
  } catch (cause) {
    if (!controller.signal.aborted) {
      startError.value = requestError(cause, '暂时无法开始讨论，请重试。')
      startConflict.value = cause instanceof ApiError && cause.status === 409
    }
  } finally {
    if (!controller.signal.aborted) {
      pending = undefined
      starting.value = false
    }
  }
}
</script>

<template>
  <div class="page discussion-page">
    <RouterLink class="back-link" :to="{ name: 'lobby' }"><span aria-hidden="true">←</span> 返回大厅</RouterLink>

    <div v-if="loading" class="panel screen-message">
      <span class="eyebrow">ROSTER PREVIEW</span>
      <h1>正在准备阵容</h1>
      <p role="status">正在读取嘉宾与议题…</p>
    </div>

    <div v-else-if="error" class="panel screen-message">
      <span class="eyebrow">ROSTER PREVIEW</span>
      <h1>阵容暂时不可用</h1>
      <p class="invalid" role="alert">{{ error }}</p>
      <button class="button secondary" type="button" @click="load">重试加载讨论</button>
    </div>

    <template v-else-if="room">
      <header class="panel roster-heading">
        <div class="roster-heading__title">
          <p class="page-label">阵容预览 / ROSTER</p>
          <h1>{{ room.topic }}</h1>
          <ul class="roster-meta" aria-label="讨论信息">
            <li class="goal">{{ goals[room.discussionGoal] }}</li>
            <li>1 位主持 · {{ room.expertCount }} 位专家</li>
            <li class="status" :data-status="room.status">{{ statusLabels[room.status] }}</li>
            <li>确认后进入演播厅</li>
          </ul>
        </div>
        <form v-if="canStart" class="confirmation" @submit.prevent="start">
          <button class="button primary" type="submit" :aria-disabled="starting">
            {{ starting ? '正在确认…' : '开始讨论' }}
          </button>
          <span class="confirmation__hint">阵容确认后不可再修改</span>
        </form>
        <RouterLink
          v-else
          class="button secondary"
          :to="{ name: 'discussion', params: { id: roomId } }"
        >查看讨论房间</RouterLink>
        <p v-if="startError" role="alert" class="notice notice--error">{{ startError }}</p>
        <p class="sr-only" role="status">{{ starting ? '正在确认开场' : '' }}</p>
      </header>

      <section v-if="host" class="roster-section host-section" aria-labelledby="host-title">
        <div class="section-header">
          <h2 id="host-title">主持人 / HOST</h2>
          <span class="section-note">掌控节奏，不站队</span>
        </div>
        <ParticipantCard :participant="host" :index="0" />
      </section>

      <section class="roster-section" aria-labelledby="expert-title">
        <div class="section-header">
          <h2 id="expert-title">专家阵容 / PANEL</h2>
          <span class="section-note">不同职业 · 不同视角 · 自由发言</span>
        </div>
        <ul class="expert-grid">
          <li v-for="(expert, index) in experts" :key="expert.id">
            <ParticipantCard :participant="expert" :index="index + 1" />
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>
