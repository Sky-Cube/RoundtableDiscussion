<script setup lang="ts">
import { computed } from 'vue'
import type { PresetParticipant } from '../../api/discussions'
import { avatarPalette, avatarStyleFor, avatarStyleLabels, seatColor } from './avatar-style'

const props = withDefaults(defineProps<{ participant: PresetParticipant; index?: number; compact?: boolean }>(), {
  index: 0,
  compact: false,
})

const style = computed(() => avatarStyleFor(props.participant, props.index))
const palette = computed(() => avatarPalette(props.participant, props.index))
const color = computed(() => seatColor(props.participant.colorKey))
const label = computed(() => avatarStyleLabels[style.value])
const anime = computed(() => style.value === 'anime')
const glasses = computed(() => ['corporate', 'scholar'].includes(style.value) && props.index % 2 === 0)
</script>

<template>
  <span
    class="avatar-portrait"
    :class="[`avatar-portrait--${style}`, { 'avatar-portrait--compact': compact }]"
    :data-avatar-style="style"
    :style="{ '--portrait-color': color, '--portrait-skin': palette.skin, '--portrait-hair': palette.hair }"
    :aria-label="`${participant.displayName}，${label}形象`"
    role="img"
  >
    <svg viewBox="0 0 120 140" aria-hidden="true">
      <circle cx="60" cy="66" r="54" class="portrait-glow" />
      <path class="portrait-body" d="M14 140c2-29 19-43 46-43s44 14 46 43z" />
      <path v-if="style !== 'casual'" class="portrait-shirt" d="M49 99h22l-11 24z" />
      <path v-if="style === 'casual'" class="portrait-hood" d="M42 103c5 9 11 14 18 14s13-5 18-14l8 9c-5 16-13 22-26 22s-21-6-26-22z" />
      <rect x="52" y="78" width="16" height="23" rx="7" class="portrait-neck" />
      <ellipse cx="60" cy="59" rx="27" ry="31" class="portrait-skin" />
      <circle cx="33" cy="63" r="5" class="portrait-skin" />
      <circle cx="87" cy="63" r="5" class="portrait-skin" />
      <path class="portrait-hair" :d="style === 'anime'
        ? 'M31 52c1-20 13-31 30-31 18 0 29 12 30 31-5-5-8-12-10-17-6 8-16 12-30 12-5 0-9 2-12 7z'
        : style === 'creative'
          ? 'M33 55c-2-20 10-33 29-33 18 0 28 10 28 29-8-7-13-13-16-19-5 9-15 14-28 14-5 0-9 3-13 9z'
          : 'M33 55c0-19 11-30 28-30 17 0 27 10 27 28-4-7-9-12-15-15-5 8-14 12-26 12-5 0-10 2-14 5z'" />
      <path v-if="style === 'anime'" class="portrait-hair" d="M36 34c8-11 20-15 31-13-7 3-11 7-13 12 9-3 17-2 23 3-8 0-15 4-20 10-6-6-13-9-21-12z" />
      <path v-if="style === 'creative'" class="portrait-hair" d="M82 29c10-2 17 5 15 15-2 8-8 12-15 10 4-5 5-9 3-13-2-4-3-8-3-12z" />
      <path v-if="style === 'scholar'" class="portrait-hair" d="M51 27c4-8 14-10 21-3 6 6 5 15-1 20-2-7-9-13-20-17z" />
      <path v-if="style === 'anchor'" class="portrait-hair" d="M37 41c9-9 24-12 38-6-8 0-14 3-18 8-7-2-14-1-20-2z" />

      <g class="portrait-face">
        <ellipse v-if="anime" cx="49" cy="64" rx="7" ry="8.5" class="portrait-eye-white" />
        <ellipse v-if="anime" cx="71" cy="64" rx="7" ry="8.5" class="portrait-eye-white" />
        <ellipse :cx="49" :cy="64" :rx="anime ? 4.1 : 2.2" :ry="anime ? 5.4 : 3.3" class="portrait-eye" />
        <ellipse :cx="71" :cy="64" :rx="anime ? 4.1 : 2.2" :ry="anime ? 5.4 : 3.3" class="portrait-eye" />
        <circle v-if="anime" cx="47.6" cy="62" r="1.3" fill="#fff" />
        <circle v-if="anime" cx="69.6" cy="62" r="1.3" fill="#fff" />
        <path d="M44 55c3-2 7-2 10 0M66 55c3-2 7-2 10 0" class="portrait-brow" />
        <path d="M60 66v6l-3 2" class="portrait-nose" />
        <path :d="style === 'anime' ? 'M54 77c4 3 8 3 12 0' : 'M55 77c3 2 7 2 10 0'" class="portrait-mouth" />
      </g>

      <g v-if="glasses" class="portrait-glasses">
        <template v-if="style === 'scholar'">
          <circle cx="49" cy="64" r="9" />
          <circle cx="71" cy="64" r="9" />
        </template>
        <template v-else>
          <rect x="38" y="58" width="20" height="12" rx="4" />
          <rect x="62" y="58" width="20" height="12" rx="4" />
        </template>
        <path d="M58 64h4M29 62l9 2M91 62l-9 2" />
      </g>

      <path v-if="style === 'corporate' || style === 'anchor'" class="portrait-tie" d="M57 101h6l3 11-6 11-6-11z" />
      <path v-if="style === 'anime'" class="portrait-ribbon" d="M52 100l8 6 8-6 4 10-12 6-12-6z" />
      <path v-if="style === 'creative'" class="portrait-neckline" d="M45 100c5 3 9 5 15 5s10-2 15-5" />
      <circle v-if="style === 'creative'" cx="87" cy="70" r="2.4" class="portrait-earring" />
    </svg>
  </span>
</template>
