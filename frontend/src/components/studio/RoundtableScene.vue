<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Participant, Utterance } from '../../api/discussions'
import { avatarStyleLabels } from './avatar-style'
import { createRoundtable, seatPositions } from './roundtable'

const props = defineProps<{ participants: Participant[]; bubble?: Utterance }>()
const stage = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const rendered = ref(false)
const projected = ref<{ x: number; y: number }[]>([])
const seats = computed(() => seatPositions(props.participants))
const layout = computed(() => JSON.stringify(seats.value.map(({ id, color, style }) => [id, color, style])))
const active = computed(() => props.participants.find(member => member.stageStatus === 'SPEAKING'))
const focus = computed(() => active.value ?? props.participants.find(member => member.id === props.bubble?.participantId))
const membersById = computed(() => new Map(props.participants.map(member => [member.id, member])))
const speech = computed(() => {
  if (active.value?.publicSpeech) {
    return { participantId: active.value.id, content: active.value.publicSpeech, frozen: false }
  }
  if (props.bubble && !active.value) {
    return { participantId: props.bubble.participantId, content: props.bubble.content, frozen: true }
  }
  return undefined
})
const activeSeat = computed(() => seats.value.find(seat => seat.id === active.value?.id))
let scene: ReturnType<typeof createRoundtable>
let observer: ResizeObserver | undefined
let textFrame: number | undefined

function resize() {
  if (stage.value && scene) projected.value = scene.render(stage.value.clientWidth, stage.value.clientHeight)
}

function draw() {
  if (!canvas.value || typeof globalThis.WebGL2RenderingContext === 'undefined') return
  scene?.dispose()
  try {
    scene = createRoundtable(canvas.value, seats.value)
    rendered.value = !!scene
    if (scene) {
      scene.setActive(active.value?.id)
      resize()
    }
  } catch {
    scene?.dispose()
    scene = undefined
    rendered.value = false
  }
}

function anchor(index: number) {
  const position = projected.value[index]
  const angle = seats.value[index]?.angle ?? 0
  return position ?? { x: 50 + Math.sin(angle) * 32, y: 46 + Math.cos(angle) * 24 }
}

onMounted(() => {
  draw()
  if (stage.value && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(resize)
    observer.observe(stage.value)
  }
})
watch(layout, draw)
watch(() => active.value?.id, id => {
  scene?.setActive(id)
})
watch(() => speech.value?.content, async () => {
  await nextTick()
  if (textFrame !== undefined) cancelAnimationFrame(textFrame)
  const text = stage.value?.querySelector<HTMLElement>('.seat-bubble--text p')
  if (!text || text.scrollHeight <= text.clientHeight || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const initialOffset = text.scrollTop
  const distance = text.scrollHeight - text.clientHeight - initialOffset
  if (distance <= 0) return
  const duration = Math.max(1000, distance * 32)
  const start = performance.now() + 500
  const scrollSpeech = (time: number) => {
    const progress = Math.min(1, Math.max(0, (time - start) / duration))
    text.scrollTop = initialOffset + distance * progress
    textFrame = progress < 1 ? requestAnimationFrame(scrollSpeech) : undefined
  }
  textFrame = requestAnimationFrame(scrollSpeech)
})
onBeforeUnmount(() => {
  observer?.disconnect()
  if (textFrame !== undefined) cancelAnimationFrame(textFrame)
  scene?.dispose()
})
</script>

<template>
  <div ref="stage" class="room-scene" :data-renderer="rendered ? 'webgl' : 'flat'">
    <canvas ref="canvas" v-show="rendered" aria-hidden="true"></canvas>
    <div v-if="!rendered" class="flat-table" aria-hidden="true"></div>

    <div class="scene-caption" aria-live="polite">
      <span class="scene-caption__dot" :data-state="active ? 'SPEAKING' : focus ? 'PREPARING' : 'IDLE'" aria-hidden="true"></span>
      <span>{{ active ? '正在发言' : focus ? '最近发言' : '等待发言' }}</span>
      <strong v-if="focus" :style="{ color: seats.find(seat => seat.id === focus?.id)?.color }">{{ focus.displayName }}</strong>
      <span class="scene-caption__style" v-if="activeSeat">{{ avatarStyleLabels[activeSeat.style] }}</span>
    </div>

    <div class="scene-note" aria-hidden="true">
      <span>REC</span>
      <i></i>
      <span>STUDIO B · CAM 01</span>
    </div>

    <div
      v-for="(seat, index) in seats"
      :key="seat.id"
      class="virtual-seat"
      :data-member="seat.id"
      :data-state="membersById.get(seat.id)?.stageStatus"
      :data-avatar-style="seat.style"
      :data-align="anchor(index).x < 34 ? 'left' : anchor(index).x > 66 ? 'right' : 'center'"
      :style="{ left: anchor(index).x + '%', top: anchor(index).y + '%', '--seat-color': seat.color }"
    >
      <span v-if="!rendered" class="flat-avatar" :data-style="seat.style" aria-hidden="true"></span>

      <div
        class="seat-bubble"
        :class="{
          'seat-bubble--text': speech?.participantId === seat.id,
          'seat-bubble--frozen': speech?.participantId === seat.id && speech?.frozen,
          'seat-bubble--active': speech?.participantId === seat.id && !speech?.frozen,
        }"
        :data-align="anchor(index).x < 34 ? 'left' : anchor(index).x > 66 ? 'right' : 'center'"
        :aria-hidden="speech?.participantId !== seat.id"
        :aria-label="speech?.participantId === seat.id ? membersById.get(seat.id)?.displayName + '的发言' : undefined"
        :tabindex="speech?.participantId === seat.id ? 0 : undefined"
      >
        <template v-if="speech?.participantId === seat.id">
          <header class="seat-speech-head">
            <span class="seat-speech-name">{{ membersById.get(seat.id)?.displayName }}</span>
            <span class="seat-speech-state">{{ speech.frozen ? '已定格' : '发言中' }}</span>
          </header>
          <p>{{ speech.content }}</p>
          <footer class="seat-speech-foot">
            <span>{{ speech.frozen ? '发言已结束 · 内容定格' : '实时转写' }}</span>
            <i v-if="!speech.frozen" aria-hidden="true"></i>
          </footer>
        </template>
      </div>

      <span class="seat-name">
        <i aria-hidden="true"></i>
        {{ membersById.get(seat.id)?.displayName }}
      </span>
    </div>
  </div>
</template>
