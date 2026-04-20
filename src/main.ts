import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './expand'

// import('vconsole').then(({ default: VConsole }) => {
//   new VConsole({ theme: 'light' })
// })

// // Enable vConsole in non-production environments (including Capacitor mobile app)
// if (import.meta.env.MODE !== 'production') {
// }

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

app.mount('#app')
