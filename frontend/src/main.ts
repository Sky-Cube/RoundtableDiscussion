import { createApp } from 'vue'
import App from './App.vue'
import { createAppRouter } from './router'
import './style.css'

const router = createAppRouter()
const app = createApp(App).use(router)
await router.isReady()
app.mount('#app')
