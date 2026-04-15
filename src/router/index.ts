import { createRouter, createWebHistory } from 'vue-router'
import WelcomeView from '../views/WelcomeView.vue'
import DiagnosisView from '../views/DiagnosisView.vue'
import FormView from '../views/FormView.vue'
import MapView from '../views/MapView.vue'
import QuestionView from '../views/QuestionView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: WelcomeView,
    },
    {
      path: '/diagnosis',
      name: 'diagnosis',
      component: DiagnosisView,
    },
    {
      path: '/form',
      name: 'form',
      component: FormView,
    },
    {
      path: '/map',
      name: 'map',
      component: MapView,
    },
    {
      path: '/question',
      name: 'question',
      component: QuestionView,
    },
  ],
})

export default router
