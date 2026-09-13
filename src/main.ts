import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { vTap } from './directives/tap'
import './expand'
import { initHiti } from '@/utils/print-lifecycle'

import('vconsole').then(({ default: VConsole }) => {
  new VConsole({ theme: 'light' })
})

// Enable vConsole in non-production environments (including Capacitor mobile app)
if (import.meta.env.MODE !== 'production') {
}

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// 全局注册 v-tap 指令 —— 解决 Capacitor WebView 上 :active 不稳的问题,
// 见 src/directives/tap.ts。
app.directive('tap', vTap)

app.mount('#app')

/**
 * App 启动 init HiTi 一次（HiTiPrinterCurrent 单 plugin 方案）。
 *
 * <p>HiTi 默认在 app 全程占 USB，进入 capture 类路由时由 router 守卫 release，
 * 离开时再 init；进入 print-related 路由（detail-analysis / print-test）时
 * 已在 init 状态，no-op；离开 print 路由时 release。
 *
 * <p>不 await：app.mount 不需要等 HiTi init 完成（HiTi 是 USB 后台资源，不影响
 * 页面渲染）。后续路由切换 / 打印调用前会通过 router 守卫 / 调用方 await。
 */
void initHiti().then(() => {
  console.log('[app] startup HiTi init done')
})
