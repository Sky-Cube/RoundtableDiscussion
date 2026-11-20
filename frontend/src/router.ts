import { createRouter, createWebHistory } from 'vue-router'
import type { RouterHistory } from 'vue-router'
import LobbyView from './views/LobbyView.vue'
import DiscussionView from './views/DiscussionView.vue'
import NotFoundView from './views/NotFoundView.vue'

export function createAppRouter(history: RouterHistory = createWebHistory()) {
  return createRouter({
    history,
    routes: [
      { path: '/', name: 'lobby', component: LobbyView },
      { path: '/discussions/:id/roster', name: 'roster', component: DiscussionView },
      { path: '/discussions/:id', name: 'discussion', component: () => import('./views/StudioView.vue') },
      { path: '/:pathMatch(.*)*', component: NotFoundView },
    ],
  })
}
