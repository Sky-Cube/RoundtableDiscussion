<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'

const route = useRoute()
const content = ref<HTMLElement>()
const inStudio = computed(() => route.name === 'discussion')

watch(() => route.path, async () => {
  await nextTick()
  if (content.value) {
    content.value.scrollTop = 0
    content.value.focus({ preventScroll: true })
  }
})

function focusContent() {
  content.value?.focus()
}
</script>

<template>
  <a class="skip-link" href="#main-content" @click.prevent="focusContent">跳到主要内容</a>
  <header class="site-header">
    <div class="site-header__inner">
      <RouterLink class="brand" :to="{ name: 'lobby' }" aria-label="AI Panel Studio 圆桌演播厅首页">
        <span class="brand-mark" aria-hidden="true">
          <svg viewBox="0 0 34 34" fill="none">
            <circle cx="17" cy="17" r="10.2" stroke="currentColor" stroke-width="1.2" />
            <ellipse cx="17" cy="17" rx="14.6" ry="6.2" stroke="currentColor" stroke-width=".9" opacity=".55" />
            <ellipse cx="17" cy="17" rx="6.2" ry="14.6" stroke="currentColor" stroke-width=".9" opacity=".35" />
            <circle cx="17" cy="17" r="2.1" fill="currentColor" />
            <circle cx="27.2" cy="17" r="1.35" fill="currentColor" opacity=".8" />
          </svg>
        </span>
        <span class="brand-copy">
          <span class="brand-title">AI Panel <em>Studio</em></span>
          <span class="brand-sub">圆桌演播厅 · ROUNDTABLE BROADCAST</span>
        </span>
      </RouterLink>

      <nav class="site-nav" aria-label="主导航">
        <span class="nav-live" :class="{ 'nav-live--on': inStudio }" aria-hidden="true">{{ inStudio ? 'ON AIR' : 'STUDIO READY' }}</span>
        <RouterLink class="nav-link" :to="{ name: 'lobby' }">讨论大厅</RouterLink>
      </nav>
    </div>
  </header>
  <main id="main-content" ref="content" tabindex="-1">
    <RouterView v-slot="{ Component, route: currentRoute }">
      <component :is="Component" :key="currentRoute.path" />
    </RouterView>
  </main>
</template>
