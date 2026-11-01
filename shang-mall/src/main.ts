import { createApp } from 'vue'
import { createPinia } from 'pinia'
import 'vant/lib/index.css'

import App from './App.vue'
import router from './router'
import '@/assets/styles.css'
import '@/assets/dialog.css'
import '@/assets/web-portal.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
