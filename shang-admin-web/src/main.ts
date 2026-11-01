import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

import '@/assets/styles.css'
import '@/assets/extra.css'
import '@/assets/element-overrides.css'
import '@/assets/modal.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 必须显式给 locale：Element Plus 默认英文，否则确认框按钮会显示成 Cancel / OK
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')