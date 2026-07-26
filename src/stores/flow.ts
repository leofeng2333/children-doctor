import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

export type FlowMode = 'long' | 'short'

const STORAGE_KEY = 'children_doctor.flow_mode'

function readStoredMode(): FlowMode {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw === 'short' || raw === 'long') return raw
  } catch {
    // localStorage 不可用时回退到默认值
  }
  return 'long'
}

function persistMode(mode: FlowMode): void {
  try {
    localStorage.setItem(STORAGE_KEY, mode)
  } catch {
    // 持久化失败可忽略，记忆仅限本次会话
  }
}

/**
 * 全局流程模式：
 *   - long  完整流程：Form → Location → Diagnosis → CaptureIntro → Capture → Question → Quiz → DetailAnalysis
 *   - short 精简流程：Form → CaptureIntro → Capture → Question → Quiz → DetailAnalysis
 *
 * 切换入口：首页右上角图标双击 → 密码弹窗（PasswordDialog）→ 切换。
 * 持久化：localStorage，键名 children_doctor.flow_mode。
 */
export const useFlowStore = defineStore('flow', () => {
  const mode = ref<FlowMode>(readStoredMode())

  const isLong = computed(() => mode.value === 'long')
  const isShort = computed(() => mode.value === 'short')

  function setMode(next: FlowMode) {
    if (next !== 'long' && next !== 'short') return
    mode.value = next
  }

  function toggle() {
    mode.value = mode.value === 'long' ? 'short' : 'long'
  }

  watch(mode, (v) => persistMode(v), { flush: 'post' })

  return { mode, isLong, isShort, setMode, toggle }
})
