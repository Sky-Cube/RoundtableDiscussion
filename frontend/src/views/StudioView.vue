<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { goals, stageLabels, statusLabels } from '../api/discussions'
import { seatColor } from '../components/studio/avatar-style'
import RoundtableScene from '../components/studio/RoundtableScene.vue'
import FinalResultModal from '../components/studio/FinalResultModal.vue'
import { useDiscussionSession } from '../composables/useDiscussionSession'
import './studio.css'

const roomId = String(useRoute().params.id)
const { room, bubble, connection, error, retry } = useDiscussionSession(roomId)
const members = computed(() => new Map(room.value?.participants.map(member => [member.id, member])))
const panels = [
  { id: 'live', label: '现场' },
  { id: 'members', label: '嘉宾' },
  { id: 'insights', label: '结论' },
] as const
type Panel = (typeof panels)[number]['id']
const tab = ref<Panel>('live')
const transcriptOpen = ref(false)
const transcript = ref<HTMLElement>()
const memberList = ref<HTMLElement>()
const selectedSeq = ref<number>()
const finalOpen = ref(false)
const finalDismissed = ref(false)
const finalTrigger = ref<HTMLButtonElement>()
const insightKinds = [{ kind: 'CONSENSUS', label: '共识' }, { kind: 'DISAGREEMENT', label: '分歧' }] as const
const utteranceLabels = { OPENING: '主持开场', EXPERT: '专家发言', FOLLOW_UP: '主持追问', BRIDGE: '主持串联', SUMMARY: '主持总结' }
const connectionLabels = { connecting: '正在连接', connected: '实时连接', reconnecting: '正在重连', offline: '连接中断', closed: '', loading: '' }
const stageEnglish = { IDLE: 'IDLE', PREPARING: 'PREPARING', SPEAKING: 'SPEAKING' } as const
const stageStates = ['IDLE', 'PREPARING', 'SPEAKING'] as const
const topicStatus = computed(() => room.value ? statusLabels[room.value.status] : '')
const expertCount = computed(() => room.value?.participants.filter(member => member.role === 'EXPERT').length ?? 0)
const speakingCount = computed(() => room.value?.participants.filter(member => member.stageStatus === 'SPEAKING').length ?? 0)
const activeMemberId = computed(() => room.value?.participants.find(member => member.stageStatus === 'SPEAKING')?.id
  ?? room.value?.participants.find(member => member.stageStatus === 'PREPARING')?.id)
const expertTranscript = computed(() => room.value?.transcript.filter(item => item.kind === 'EXPERT').length ?? 0)

watch(() => room.value?.status, status => {
  if (status === 'COMPLETED' && !finalDismissed.value) finalOpen.value = true
}, { immediate: true })

watch(activeMemberId, async id => {
  if (!id) return
  await nextTick()
  const list = memberList.value
  if (!list) return
  const target = list.querySelector<HTMLElement>(`[data-member="${id}"]`)
  if (typeof target?.scrollIntoView === 'function') target.scrollIntoView({ block: 'nearest' })
})

function moveTab(event: KeyboardEvent, index: number) {
  let next = index
  if (event.key === 'ArrowRight') next = (index + 1) % panels.length
  else if (event.key === 'ArrowLeft') next = (index + panels.length - 1) % panels.length
  else if (event.key === 'Home') next = 0
  else if (event.key === 'End') next = panels.length - 1
  else return
  event.preventDefault()
  tab.value = panels[next].id
  document.getElementById('studio-tab-' + tab.value)?.focus()
}

function scrollLatest() {
  if (transcript.value) transcript.value.scrollTop = transcript.value.scrollHeight
}

async function toggleTranscript() {
  transcriptOpen.value = !transcriptOpen.value
  if (!transcriptOpen.value) return
  selectedSeq.value = undefined
  await nextTick()
  scrollLatest()
}

async function jumpToSpeech(seq: number) {
  tab.value = 'live'
  transcriptOpen.value = true
  selectedSeq.value = seq
  await nextTick()
  const list = transcript.value
  const target = list?.querySelector<HTMLElement>(`[data-speech="${seq}"]`)
  if (!list || !target) return
  list.scrollTop += target.getBoundingClientRect().top - list.getBoundingClientRect().top - 8
  target.focus({ preventScroll: true })
}

function goToLatest() {
  selectedSeq.value = undefined
  scrollLatest()
}

async function closeFinal() {
  finalOpen.value = false
  finalDismissed.value = true
  await nextTick()
  finalTrigger.value?.focus()
}

async function viewTranscriptFromFinal() {
  finalOpen.value = false
  finalDismissed.value = true
  transcriptOpen.value = true
  await nextTick()
  scrollLatest()
}

async function jumpFromFinal(seq: number) {
  finalOpen.value = false
  finalDismissed.value = true
  await jumpToSpeech(seq)
}

function openFinal() {
  finalOpen.value = true
}

watch(() => room.value?.transcript.length, async () => {
  const list = transcript.value
  if (!list || list.scrollHeight - list.scrollTop - list.clientHeight > 24) return
  await nextTick()
  scrollLatest()
})

onBeforeUnmount(() => {
  finalOpen.value = false
})
</script>

<template>
  <div class="studio" :data-room-status="room?.status">
    <header class="studio-header">
      <RouterLink class="back-link" :to="{ name: 'lobby' }"><span aria-hidden="true">←</span> 返回大厅</RouterLink>
      <div class="studio-header__title">
        <div class="studio-header__eyebrow">
          <span class="eyebrow">ROUND 01 / 圆桌议题</span>
          <span v-if="room" class="studio-mode"><i aria-hidden="true"></i> 自由发言 · 非轮询</span>
        </div>
        <h1 :title="room?.topic">{{ room?.topic ?? '讨论房间' }}</h1>
      </div>
      <div v-if="room" class="studio-header__meta">
        <span class="goal">{{ goals[room.discussionGoal] }}</span>
        <span class="status" :data-status="room.status">{{ topicStatus }}</span>
        <span v-if="connectionLabels[connection]" class="connection-state" :data-state="connection" role="status">
          <i aria-hidden="true"></i>{{ connectionLabels[connection] }}
        </span>
        <button v-if="room.status === 'COMPLETED'" ref="finalTrigger" type="button" class="final-result-trigger" @click="openFinal">查看最终结果</button>
      </div>
    </header>

    <div v-if="!room" class="panel screen-message studio-loading">
      <p v-if="error" role="alert" class="invalid">{{ error }}</p>
      <p v-else role="status">正在加载讨论…</p>
      <button v-if="error" class="button secondary" type="button" @click="retry">重试加载讨论</button>
    </div>

    <template v-else>
      <div class="studio-alerts">
        <div v-if="connection === 'offline'" class="studio-alert" role="status">
          <span>{{ error || '实时连接已中断，仍可查看已收到的内容。' }}</span>
          <button class="text-button" type="button" @click="retry">恢复连接</button>
        </div>
        <p v-if="room.status === 'FAILED'" class="studio-alert">讨论暂时无法继续，可查看已公开实录。</p>
        <div v-if="room.status === 'DRAFT'" class="studio-alert">
          <span>本场讨论等待确认。</span>
          <RouterLink class="text-button" :to="{ name: 'roster', params: { id: roomId } }">查看待确认阵容</RouterLink>
        </div>
      </div>

      <div class="studio-tabs" role="tablist" aria-label="演播厅区域">
        <button
          v-for="(item, index) in panels"
          :id="'studio-tab-' + item.id"
          :key="item.id"
          type="button"
          role="tab"
          :aria-controls="'studio-panel-' + item.id"
          :aria-selected="tab === item.id"
          :tabindex="tab === item.id ? 0 : -1"
          @click="tab = item.id"
          @keydown="moveTab($event, index)"
        >{{ item.label }}</button>
      </div>

      <div class="studio-grid" :data-tab="tab">
        <section id="studio-panel-members" class="panel studio-members" aria-labelledby="members-title">
          <header class="studio-panel-head">
            <div>
              <span class="eyebrow">PARTICIPANT MONITOR</span>
              <h2 id="members-title">圆桌成员状态</h2>
            </div>
            <span class="panel-count">{{ room.participants.length.toString().padStart(2, '0') }} SEATS</span>
          </header>
          <p class="studio-panel-sub">1 位主持人 · {{ expertCount }} 位专家 <span>{{ speakingCount ? speakingCount + ' 位发言中' : '现场待机' }}</span></p>
          <div class="stage-legend" role="group" aria-label="专家状态示例">
            <span v-for="state in stageStates" :key="state" class="stage-label" :data-state="state">
              <i aria-hidden="true"></i>{{ stageEnglish[state] }} {{ stageLabels[state] }}
            </span>
          </div>
          <ul ref="memberList" class="member-status-list">
            <li
              v-for="member in room.participants"
              :key="member.id"
              :data-member="member.id"
              :data-state="member.stageStatus"
              :style="{ '--seat-color': seatColor(member.colorKey) }"
            >
              <div class="member-status-heading">
                <span class="member-color" aria-hidden="true"></span>
                <span class="member-name">{{ member.displayName }}</span>
                <span class="stage-label" :data-state="member.stageStatus">
                  <i aria-hidden="true"></i>{{ stageEnglish[member.stageStatus] }} {{ stageLabels[member.stageStatus] }}
                </span>
              </div>
              <p class="member-role">{{ member.role === 'HOST' ? '主持人' : '专家' }} · {{ member.profession }}</p>
              <p class="member-focus"><span>公开关注点</span>：{{ member.publicFocus ?? '暂无' }}</p>
            </li>
          </ul>
          <footer class="studio-panel-foot">
            <span><i aria-hidden="true"></i> SSE 实时同步</span>
            <span>{{ expertTranscript }} 条专家发言</span>
          </footer>
        </section>

        <section id="studio-panel-live" class="studio-live" aria-label="圆桌现场">
          <RoundtableScene
            :participants="room.participants"
            :bubble="bubble"
            :ended="room.status === 'COMPLETED' || room.status === 'FAILED'"
          />
        </section>

        <section id="studio-panel-insights" class="panel studio-insights" aria-labelledby="insights-title">
          <header class="studio-panel-head">
            <div>
              <span class="eyebrow">ROUND SYNTHESIS</span>
              <h2 id="insights-title">讨论总结</h2>
            </div>
            <button v-if="room.status === 'COMPLETED'" type="button" class="text-button" @click="openFinal">最终结果</button>
          </header>
          <div class="insights-body">
            <p v-if="room.insightStatus === 'STALE'" class="insight-stale" role="status">观点更新暂缓，保留上次结果。</p>
            <p v-else-if="room.insightStatus === 'PENDING'" class="muted">等待提炼共识与分歧。</p>
            <p v-else-if="room.insights.length === 0" class="muted">本轮暂无有足够证据的共识或分歧。</p>
            <section v-if="room.summary" class="host-summary">
              <span class="eyebrow">HOST SUMMARY</span>
              <h3>主持总结</h3>
              <p>{{ room.summary }}</p>
            </section>
            <div v-for="group in insightKinds" :key="group.kind" class="insight-group" :data-kind="group.kind">
              <h3><i aria-hidden="true"></i>{{ group.label }} <small>{{ group.kind }}</small></h3>
              <ul>
                <li v-for="insight in room.insights.filter(item => item.kind === group.kind)" :key="insight.id">
                  <p>{{ insight.text }}</p>
                  <div class="insight-evidence">
                    <button
                      v-for="seq in insight.evidenceUtteranceSeqs"
                      :key="seq"
                      type="button"
                      class="citation"
                      @click="jumpToSpeech(seq)"
                    >证据 #{{ seq }}</button>
                  </div>
                </li>
              </ul>
            </div>
          </div>
          <footer class="studio-panel-foot summary-foot">
            <span><i aria-hidden="true"></i> 随讨论实时提炼</span>
            <span>{{ room.insightStatus === 'FRESH' ? '最新' : room.insightStatus }}</span>
          </footer>
        </section>
      </div>

      <section class="transcript-drawer" :class="{ 'is-open': transcriptOpen }" :data-tab-active="tab === 'live'" aria-label="讨论实录">
        <div class="transcript-header">
          <button
            class="transcript-toggle" type="button" :aria-expanded="transcriptOpen"
            aria-controls="studio-transcript" @click="toggleTranscript"
          >
            <span class="transcript-toggle__icon" aria-hidden="true">≡</span>
            <span class="transcript-toggle__copy">
              <strong>{{ transcriptOpen ? '收起完整实录' : '查看完整实录' }}</strong>
              <small>{{ room.transcript.length }} 条 · ROUND 01</small>
            </span>
            <span class="transcript-toggle__chevron" aria-hidden="true">{{ transcriptOpen ? '⌃' : '⌄' }}</span>
          </button>
          <button v-if="transcriptOpen && room.transcript.length" class="text-button" type="button" @click="goToLatest">回到最新 ↓</button>
        </div>
        <div id="studio-transcript" v-show="transcriptOpen" ref="transcript" class="transcript-scroll" tabindex="0" aria-label="实录列表">
          <p v-if="!room.transcript.length" class="muted">还没有公开发言。</p>
          <ol v-else>
            <li
              v-for="speech in room.transcript"
              :key="speech.seq"
              :data-speech="speech.seq"
              :data-selected="selectedSeq === speech.seq"
              :style="{ '--seat-color': seatColor(members.get(speech.participantId)?.colorKey ?? '') }"
              tabindex="-1"
            >
              <header>
                <strong>{{ members.get(speech.participantId)?.displayName }}</strong>
                <span>{{ members.get(speech.participantId)?.profession }} · {{ members.get(speech.participantId)?.title }}</span>
                <span>{{ utteranceLabels[speech.kind] }} #{{ speech.seq }}</span>
              </header>
              <p>{{ speech.content }}</p>
              <button v-if="speech.replyToUtteranceSeq" type="button" class="citation" @click="jumpToSpeech(speech.replyToUtteranceSeq)">
                回应 #{{ speech.replyToUtteranceSeq }}
              </button>
            </li>
          </ol>
        </div>
      </section>
    </template>

    <FinalResultModal
      v-if="finalOpen && room"
      :open="true"
      :room="room"
      @close="closeFinal"
      @view-transcript="viewTranscriptFromFinal"
      @citation="jumpFromFinal"
    />
  </div>
</template>
