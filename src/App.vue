<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import DevToolsLauncher from './components/DevToolsLauncher.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import { useAnalysisStore } from '@/stores'
import { releaseHiti } from '@/utils/print-lifecycle'
import { DevToolsDialog } from '@/plugins/dev-tools'

/**
 * 全局开发者工具弹窗状态。
 *
 * <p>{@code DevToolsLauncher} 固定在所有页面右上角，双击调用
 * {@link DevToolsDialog.show()} 弹 native Android Dialog；弹窗位于
 * WindowManager TYPE_APPLICATION_PANEL 层，永远在 WebView / preview surface
 * 之上，且只在 show() 期间占屏，关闭后整张 native window 一起消失，preview
 * 完全不受影响。
 *
 * <p>弹窗不再用 Vue 组件渲染，因为 WebView 的 z-index 永远穿不出原生 View 层级。
 */
const dialogOpen = ref(false)

const handleOpen = async () => {
  if (dialogOpen.value) return
  dialogOpen.value = true
  try {
    // native dialog 自管关闭；resolve 之后把状态置回 false 即可。
    await DevToolsDialog.show()
  } catch (err) {
    // 调用失败（例如 web 平台 / 重复 show / Activity 销毁）只记日志，不打断用户。
    // eslint-disable-next-line no-console
    console.warn('[DevToolsDialog] show failed:', err)
  } finally {
    dialogOpen.value = false
  }
}

/**
 * 全局 AI 分析失败弹窗。
 *
 * <p>背景：CameraCaptureView.startAnalysis() 现在 fire-and-forget 调用
 * {@code analysisStore.start()}，上传完成后立即 router.push 到 /question。
 * AI 分析 API 在后台跑，跑完时 CameraCaptureView 已被销毁 —— 因此
 * 失败提示不能再挂在那个页面，必须挂在全局。
 *
 * <p>{@code analysisStore.start()} 内部 try/catch 把错误写到
 * {@code analysisStore.error}，这里用 computed 监听：error 一旦非空
 * 就弹窗，用户点「返回」后 reset() + 回到 /capture-intro 重拍。
 *
 * <p>app 启动时 reset() 一次，避免上一次会话残留的 error 让本次启动
 * 立即弹窗（典型场景：上次 APP 崩溃在分析阶段，store 持久化在了内存里）。
 */
const router = useRouter()
const analysisStore = useAnalysisStore()

onMounted(() => {
  // 清掉上一会话残留的 error/result,确保 app 启动是干净状态
  analysisStore.reset()
})

const showAnalysisErrorDialog = computed(() => !!analysisStore.error)

const handleAnalysisErrorConfirm = async () => {
  // 先 reset 再跳转 —— 跳转是异步的,如果先跳转,App.vue 还可能在
  // 新路由下短暂监听到 error 触发再次弹窗
  analysisStore.reset()
  // 返回拍照前显式 release HiTi USB,让 UVC camera 能独占 bus,
  // 避免 detail-analysis(已 init HiTi)→ capture-intro 时端口冲突。
  // router.beforeEach 在 to=capture 时也会再调一次 releaseHiti(),幂等无副作用。
  await releaseHiti()
  await router.push({ name: 'capture-intro' })
}
</script>

<template>
  <RouterView />
  <DevToolsLauncher @open="handleOpen" />
  <!-- AI 分析失败的全局兜底弹窗 —— 监听 analysisStore.error,
       在任何页面都会触发 (CameraCaptureView 触发 AI 分析后台跑,等 API
       返回时用户已经在 /question / /quiz-intro 等其它页面)。 -->
  <ConfirmDialog
    :visible="showAnalysisErrorDialog"
    title="提示"
    message="出现问题啦~可能是人脸拍摄不够标准或者网络波动，点击返回重试"
    confirm-text="返回"
    single-button
    @confirm="handleAnalysisErrorConfirm"
  />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body {
  font-family:
    -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
}
</style>

