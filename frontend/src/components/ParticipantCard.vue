<script setup lang="ts">
import { computed } from 'vue'
import { perspectives } from '../api/discussions'
import type { PresetParticipant } from '../api/discussions'
import AvatarPortrait from './studio/AvatarPortrait.vue'
import { avatarStyleFor, seatColor } from './studio/avatar-style'

const props = defineProps<{ participant: PresetParticipant; index?: number }>()
const style = computed(() => avatarStyleFor(props.participant, props.index ?? 0))
const portraitColor = computed(() => seatColor(props.participant.colorKey))
</script>

<template>
  <article
    class="participant-card"
    :data-color="participant.colorKey"
    :data-avatar-style="style"
    :class="{ 'participant-card--host': participant.role === 'HOST' }"
    :style="{ '--portrait-color': portraitColor }"
  >
    <div class="participant-card__header">
      <AvatarPortrait :participant="participant" :index="index ?? 0" compact />
      <div class="participant-card__identity">
        <p class="participant-card__eyebrow">
          {{ participant.role === 'HOST' ? '主持人 / HOST' : '专家 / EXPERT' }}
        </p>
        <h3>{{ participant.displayName }}</h3>
        <p>{{ participant.profession }} <span aria-hidden="true">·</span> {{ participant.title }}</p>
      </div>
      <span v-if="participant.role === 'EXPERT'" class="perspective">{{ perspectives[participant.perspective] }}</span>
    </div>
    <dl v-if="participant.role === 'EXPERT'" class="participant-card__stance">
      <dt>初始立场</dt>
      <dd>{{ participant.stance }}</dd>
    </dl>
  </article>
</template>
