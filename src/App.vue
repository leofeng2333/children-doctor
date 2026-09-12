<script setup lang="ts">
import { ref } from 'vue'
import { RouterView } from 'vue-router'
import DevToolsLauncher from './components/DevToolsLauncher.vue'
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
</script>

<template>
  <RouterView />
  <DevToolsLauncher @open="handleOpen" />
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

