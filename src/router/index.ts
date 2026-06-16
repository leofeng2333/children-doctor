import { createRouter, createWebHistory } from 'vue-router'
import WelcomeView from '../views/WelcomeView.vue'
import DiagnosisView from '../views/DiagnosisView.vue'
import FormView from '../views/FormView.vue'
import MapView from '../views/MapView.vue'
import LocationView from '../views/LocationView.vue'
import QuestionView from '../views/QuestionView.vue'
import CaptureIntroView from '../views/CaptureIntroView.vue'
import CameraCaptureView from '../views/CameraCaptureView.vue'
import DetailAnalysisView from '../views/DetailAnalysisView.vue'
import QuizView from '../views/QuizView.vue'
import QuizIntroView from '../views/QuizIntroView.vue'
import PrintTestView from '../views/PrintTestView.vue'
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
      path: '/location',
      name: 'location',
      component: LocationView,
    },
    {
      path: '/question',
      name: 'question',
      component: QuestionView,
    },
    {
      path: '/capture-intro',
      name: 'capture-intro',
      component: CaptureIntroView,
    },
    {
      path: '/capture',
      name: 'capture',
      component: CameraCaptureView,
    },
    {
      path: '/detail-analysis',
      name: 'detail-analysis',
      component: DetailAnalysisView,
    },
    {
      path: '/quiz-intro',
      name: 'quiz-intro',
      component: QuizIntroView,
    },
    {
      path: '/quiz',
      name: 'quiz',
      component: QuizView,
    },
    {
      path: '/print-test',
      name: 'print-test',
      component: PrintTestView,
    },
  ],
})

export default router
