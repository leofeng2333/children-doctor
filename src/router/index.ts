import { createRouter, createWebHistory } from 'vue-router'
import WelcomeView from '../views/WelcomeView.vue'
import FormView from '../views/FormView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: WelcomeView,
    },
    {
      path: '/form',
      name: 'form',
      component: FormView,
    },
  ],
})

export default router
