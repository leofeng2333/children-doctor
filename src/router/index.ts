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
import { initHiti, releaseHiti } from '@/utils/print-lifecycle'

/**
 * 路由 meta: HiTi USB 占用生命周期标记。
 *
 * <p>HiTi 默认在 app 启动时 init 一次（HiTiPrinterCurrent 单 plugin），整个 app
 * 期间保持 init 状态 —— 除非路由 meta 显式要求 release。
 *
 * <p>meta 取值：
 * <ul>
 *   <li>{@code usbHiti: 'capture'} —— 进入此路由时 release HiTi（让 UVC camera
 *       独占 USB 拍照），离开时 init HiTi（恢复 HiTi 占用）</li>
 *   <li>{@code usbHiti: 'print'} —— 此路由使用 HiTi 打印。路由激活期间 HiTi 保持
 *       init 状态（无需额外动作，app 启动已 init）；离开此路由时 release HiTi</li>
 *   <li>未设置 —— 普通路由，不触发任何 init/release</li>
 * </ul>
 */
declare module 'vue-router' {
  interface RouteMeta {
    usbHiti?: 'capture' | 'print'
  }
}

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
      meta: { usbHiti: 'capture' },
    },
    {
      path: '/capture',
      name: 'capture',
      component: CameraCaptureView,
      meta: { usbHiti: 'capture' },
    },
    {
      path: '/detail-analysis',
      name: 'detail-analysis',
      component: DetailAnalysisView,
      meta: { usbHiti: 'print' },
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
      meta: { usbHiti: 'print' },
    },
  ],
})

/**
 * 路由级 HiTi USB 占用守卫。
 *
 * <p>核心规则（HiTi 默认 init，单 plugin）：
 * <pre>
 *   to=capture    → release
 *   from=capture  → init
 *   from=print    → release
 *   to=print      → init（兜底：app 启动 race / 中途被 release 后回到 print 时
 *                       serviceConnector 可能为 null,需要重新 register。native 端
 *                       init 幂等:serviceConnector != null 时 log skip 不重复 register）
 *   其它任意组合   → noop
 * </pre>
 *
 * <p>{@code release} 必须 await 完成再 {@code next()}，否则 native 端还在占 USB 时
 * 路由已经跳过去（典型场景：detail-analysis → capture，Camera2 openCamera 抢不到 USB）。
 */
router.beforeEach(async (to, from, next) => {
  const toU = to.meta.usbHiti
  const fromU = from.meta.usbHiti

  // 进入 capture 路由:release HiTi,让 UVC camera 独占 USB
  if (toU === 'capture') {
    await releaseHiti()
    next()
    return
  }

  // 进入 print 路由:init HiTi(幂等兜底)
  // - cover 直接进 print 的路径(welcome → /print-test,或 /question → /detail-analysis
  //   这种 from 无 meta 的情况),此时 from=capture 规则不会触发
  // - cover app 启动 race:main.ts 的 initHiti() 是 fire-and-forget,user 快速进
  //   print 页面可能 init 还没完成,这里再 init 一次保险
  // - cover 中途被 release 的路径(我刚加的 App.vue handleAnalysisErrorConfirm 也会
  //   调 releaseHiti;释放后 serviceConnector=null,回到 print 路由时必须再 init)
  // native 端 manager.init() 幂等:serviceConnector!=null 时只 log skip,不重复 register
  if (toU === 'print') {
    await initHiti()
    next()
    return
  }

  // 离开 capture 路由:init HiTi,恢复 HiTi 占用
  if (fromU === 'capture') {
    await initHiti()
    next()
    return
  }

  // 离开 print 路由:release HiTi(用户离开"打印准备"页面,释放 USB 给其它设备)
  if (fromU === 'print') {
    await releaseHiti()
    next()
    return
  }

  // 普通路由相互切换 / 其它任意组合:noop
  next()
})

export default router
