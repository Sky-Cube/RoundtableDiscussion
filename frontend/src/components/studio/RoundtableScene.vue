<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { Participant, Utterance } from '../../api/discussions'
import { createRoundtable, seatPositions, type SeatAnchor } from './roundtable'
import { resolveSceneSpeech } from './scene-speech'

const props = defineProps<{ participants: Participant[]; bubble?: Utterance; ended?: boolean }>()
const stage = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const rendered = ref(false)
const projected = ref<SeatAnchor[]>([])
const seats = computed(() => seatPositions(props.participants))
const layout = computed(() => JSON.stringify(seats.value.map(({ id, color, style }) => [id, color, style])))
const active = computed(() => props.participants.find(member => member.stageStatus === 'SPEAKING'))
const membersById = computed(() => new Map(props.participants.map(member => [member.id, member])))
const speech = computed(() => resolveSceneSpeech(props.participants, props.bubble, props.ended ?? false))
// 气泡仍在展示或正在生成下一条发言时都视为正在发言，与左侧成员状态保持同一判定口径
const speaker = computed(() => props.participants.find(member => member.id === (active.value?.id ?? speech.value?.participantId)))
const stageSize = ref({ width: 0, height: 0 })
let scene: ReturnType<typeof createRoundtable>
let observer: ResizeObserver | undefined

function resize() {
  if (!stage.value || !scene) return
  stageSize.value = { width: stage.value.clientWidth, height: stage.value.clientHeight }
  projected.value = scene.render(stageSize.value.width, stageSize.value.height)
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

function headAnchor(index: number) {
  const angle = seats.value[index]?.angle ?? 0
  return projected.value[index]?.head ?? { x: 50 + Math.sin(angle) * 32, y: 46 + Math.cos(angle) * 24 }
}

function anchorPixelY(index: number) {
  return (headAnchor(index).y / 100) * stageSize.value.height
}

// 气泡向上展开约需 224px；空间不足时改为贴住场景顶部，避免被 overflow 裁掉首行
function bubbleTop(index: number) {
  const y = anchorPixelY(index)
  return y < 224 ? `${8 - y}px` : undefined
}

// 姓名牌极小尺寸下也会顶出容器，这里在贴近顶部时改挂到角色下方
function platePlacement(index: number): 'above' | 'below' {
  return anchorPixelY(index) < 44 ? 'below' : 'above'
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
watch(() => (speech.value ? `${speech.value.participantId}:${speech.value.live}` : ''), async () => {
  await nextTick()
  const text = stage.value?.querySelector<HTMLElement>('.seat-bubble--text p')
  // 每条发言都从首行开始展示，超长内容交给用户滚动，避免自动滚动把开头留在可视区外
  if (text) text.scrollTop = 0
})
onBeforeUnmount(() => {
  observer?.disconnect()
  scene?.dispose()
})
</script>

<template>
  <div ref="stage" class="room-scene" :data-renderer="rendered ? 'webgl' : 'flat'">
    <canvas ref="canvas" v-show="rendered" aria-hidden="true"></canvas>
    <div v-if="!rendered" class="flat-table" aria-hidden="true"></div>

    <div class="scene-caption" aria-live="polite">
      <span class="scene-caption__dot" :data-state="speaker ? 'SPEAKING' : 'IDLE'" aria-hidden="true"></span>
      <span>{{ speaker ? '正在发言' : '等待发言' }}</span>
      <strong v-if="speaker" :style="{ color: seats.find(seat => seat.id === speaker?.id)?.color }">{{ speaker.displayName }}</strong>
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
      :data-align="headAnchor(index).x < 34 ? 'left' : headAnchor(index).x > 66 ? 'right' : 'center'"
      :style="{ left: headAnchor(index).x + '%', top: headAnchor(index).y + '%', '--seat-color': seat.color }"
    >
      <span v-if="!rendered" class="flat-avatar" :data-style="seat.style" aria-hidden="true"></span>

      <div
        class="seat-bubble"
        :class="{
          'seat-bubble--text': speech?.participantId === seat.id,
          'seat-bubble--frozen': speech?.participantId === seat.id && !speech?.live,
          'seat-bubble--active': speech?.participantId === seat.id && speech?.live,
        }"
        :data-align="headAnchor(index).x < 34 ? 'left' : headAnchor(index).x > 66 ? 'right' : 'center'"
        :data-side="bubbleTop(index) ? 'pinned' : 'anchor'"
        :style="bubbleTop(index) ? { '--bubble-top': bubbleTop(index) } : undefined"
        :aria-hidden="speech?.participantId !== seat.id"
        :aria-label="speech?.participantId === seat.id ? membersById.get(seat.id)?.displayName + '的发言' : undefined"
        :tabindex="speech?.participantId === seat.id ? 0 : undefined"
      >
        <template v-if="speech?.participantId === seat.id">
          <header class="seat-speech-head">
            <span class="seat-speech-name">{{ membersById.get(seat.id)?.displayName }}</span>
            <span class="seat-speech-state">发言中</span>
          </header>
          <p>{{ speech.content }}</p>
          <footer class="seat-speech-foot">
            <span>{{ speech.live ? '实时转写' : '完整发言 · 已入实录' }}</span>
            <i v-if="speech.live" aria-hidden="true"></i>
          </footer>
        </template>
      </div>
    </div>

    <div
      v-for="(seat, index) in seats"
      v-show="speech?.participantId !== seat.id"
      :key="`plate-${seat.id}`"
      class="seat-plate"
      :data-state="membersById.get(seat.id)?.stageStatus"
      :data-placement="platePlacement(index)"
      :style="{ left: headAnchor(index).x + '%', top: headAnchor(index).y + '%', '--seat-color': seat.color }"
      aria-hidden="true"
    >
      <i></i>{{ membersById.get(seat.id)?.displayName }}
      <em v-if="seat.participant.role === 'HOST'">主持</em>
    </div>
  </div>
</template>
