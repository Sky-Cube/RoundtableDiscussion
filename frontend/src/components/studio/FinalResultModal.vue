<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import type { DiscussionSnapshot } from '../../api/discussions'
import AvatarPortrait from './AvatarPortrait.vue'
import { avatarStyleFor, avatarStyleLabels } from './avatar-style'

const props = defineProps<{ open: boolean; room: DiscussionSnapshot }>()
const emit = defineEmits<{
  (event: 'close'): void
  (event: 'view-transcript'): void
  (event: 'citation', seq: number): void
}>()

const dialog = ref<HTMLDialogElement>()
const closeButton = ref<HTMLButtonElement>()

const consensus = computed(() => props.room.insights.filter(item => item.kind === 'CONSENSUS'))
const disagreements = computed(() => props.room.insights.filter(item => item.kind === 'DISAGREEMENT'))
const participantRows = computed(() => props.room.participants.map((member, index) => ({
  member,
  style: avatarStyleFor(member, index),
})))
const duration = computed(() => {
  if (!props.room.startedAt || !props.room.endedAt) return '—'
  const seconds = Math.max(0, Math.round((Date.parse(props.room.endedAt) - Date.parse(props.room.startedAt)) / 1000))
  return `${Math.floor(seconds / 60)} 分 ${String(seconds % 60).padStart(2, '0')} 秒`
})

function close() {
  emit('close')
}

function onDialogClick(event: MouseEvent) {
  if (event.target === event.currentTarget) close()
}

async function syncOpen(open: boolean) {
  const element = dialog.value
  if (!element) return
  if (open) {
    if (!element.open) {
      if (typeof element.showModal === 'function') element.showModal()
      else element.setAttribute('open', '')
    }
    await nextTick()
    closeButton.value?.focus()
  } else if (element.open) {
    if (typeof element.close === 'function') element.close()
    else element.removeAttribute('open')
  }
}

watch(() => props.open, syncOpen, { immediate: true })
onMounted(() => {
  if (props.open) void syncOpen(true)
})
</script>

<template>
  <dialog
    ref="dialog"
    class="result-dialog"
    aria-labelledby="result-title"
    aria-describedby="result-summary"
    @cancel.prevent="close"
    @click="onDialogClick"
  >
    <div class="result-modal">
      <header class="result-modal__head">
        <div>
          <span class="eyebrow">ROUND COMPLETE / 圆桌结论</span>
          <h2 id="result-title">本轮讨论<br /><em>落下帷幕</em></h2>
        </div>
        <button ref="closeButton" type="button" class="result-modal__close" aria-label="关闭最终结果" @click="close">
          <span aria-hidden="true">✕</span>
        </button>
      </header>

      <p id="result-summary" class="result-modal__topic">{{ room.topic }}</p>

      <dl class="result-stats">
        <div><dt>专家</dt><dd>{{ room.expertCount }} 位</dd></div>
        <div><dt>发言</dt><dd>{{ room.transcript.filter(item => item.kind === 'EXPERT').length }} 条</dd></div>
        <div><dt>用时</dt><dd>{{ duration }}</dd></div>
        <div><dt>状态</dt><dd>已结束</dd></div>
      </dl>

      <section v-if="room.summary" class="result-summary">
        <span class="eyebrow">HOST SUMMARY / 主持总结</span>
        <p>{{ room.summary }}</p>
      </section>

      <div class="result-grid">
        <section class="result-section" data-kind="CONSENSUS">
          <header><span class="result-mark result-mark--consensus">✓</span><h3>共识</h3><small>{{ consensus.length }}</small></header>
          <ul v-if="consensus.length">
            <li v-for="insight in consensus" :key="insight.id">
              <p>{{ insight.text }}</p>
              <button
                v-for="seq in insight.evidenceUtteranceSeqs"
                :key="seq"
                type="button"
                class="citation"
                @click="emit('citation', seq)"
              >证据 #{{ seq }}</button>
            </li>
          </ul>
          <p v-else class="result-empty">没有形成足够证据的共识。</p>
        </section>

        <section class="result-section" data-kind="DISAGREEMENT">
          <header><span class="result-mark result-mark--disagreement">≠</span><h3>分歧</h3><small>{{ disagreements.length }}</small></header>
          <ul v-if="disagreements.length">
            <li v-for="insight in disagreements" :key="insight.id">
              <p>{{ insight.text }}</p>
              <button
                v-for="seq in insight.evidenceUtteranceSeqs"
                :key="seq"
                type="button"
                class="citation"
                @click="emit('citation', seq)"
              >证据 #{{ seq }}</button>
            </li>
          </ul>
          <p v-else class="result-empty">本轮没有记录到明确分歧。</p>
        </section>
      </div>

      <section class="result-participants" aria-label="本场嘉宾">
        <div v-for="row in participantRows" :key="row.member.id" class="result-person">
          <AvatarPortrait :participant="row.member" :index="row.member.role === 'HOST' ? 0 : room.participants.indexOf(row.member)" compact />
          <span>
            <strong>{{ row.member.displayName }}</strong>
            <small>{{ row.member.role === 'HOST' ? '主持人' : row.member.profession }} · {{ avatarStyleLabels[row.style] }}</small>
          </span>
        </div>
      </section>

      <footer class="result-modal__actions">
        <button type="button" class="button secondary" @click="emit('view-transcript')">查看完整实录</button>
        <button type="button" class="button primary" @click="close">关闭结果</button>
      </footer>
    </div>
  </dialog>
</template>
