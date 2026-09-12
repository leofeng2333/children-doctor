<script setup lang="ts">
/**
 * 开发者工具弹窗：编辑拍照方向校准 + 退出应用。
 *
 * <p>校准字段写入 dual-camera 插件的配置 JSON，下次 startPreview 自动读取生效
 * （不立即 setSlotCalibration——用户选项确认走冷启动路径）。
 *
 * <p>退出应用走与首页一致的二次确认（ConfirmDialog），防止误触。
 */
import { computed, ref, watch } from 'vue'
import ConfirmDialog from './ConfirmDialog.vue'
import { DualCamera } from '@/plugins/dual-camera'

interface SlotForm {
  previewRotation: number
  captureRotation: number
  mirror: boolean
  /** 数字缩放倍数（1.0 = 原画）。UI 限制 [1.0, 4.0]，落入 native 时夹紧到 [1.0, 10.0]。 */
  zoom: number
}

type SlotTuple = [SlotForm, SlotForm]

/** 弹窗允许的最大 zoom（与 Camera2 实际可用的 4x 量级匹配；保存时归一化函数会保证 ≥1.0）。 */
const ZOOM_INPUT_MAX = 4.0

const ROTATION_OPTIONS = [0, 90, 180, 270] as const

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const emptySlot = (): SlotForm => ({
  previewRotation: 0,
  captureRotation: 0,
  mirror: false,
  zoom: 1.0,
})

const form = ref<{ slots: SlotTuple }>({
  slots: [emptySlot(), emptySlot()],
})

const loading = ref(false)
const saving = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

const isDirty = ref(false)
const showExitConfirm = ref(false)

const snapshot = ref<string>('')

const slotsToTuple = (slots: ReadonlyArray<{ previewRotation: number; captureRotation: number; mirror: boolean; zoom: number }>): SlotTuple => {
  const s0 = slots[0]
  const s1 = slots[1]
  return [
    {
      previewRotation: s0?.previewRotation ?? 0,
      captureRotation: s0?.captureRotation ?? 0,
      mirror: s0?.mirror ?? false,
      zoom: clampZoom(s0?.zoom ?? 1.0),
    },
    {
      previewRotation: s1?.previewRotation ?? 0,
      captureRotation: s1?.captureRotation ?? 0,
      mirror: s1?.mirror ?? false,
      zoom: clampZoom(s1?.zoom ?? 1.0),
    },
  ]
}

/**
 * 把 input 输入实时夹紧到 [1.0, ZOOM_INPUT_MAX] 区间，避免 NaN / 0 / 负数提交。
 * 为什么不用 v-model.number + parseFloat：原生 number input 在用户清空时会变空字符串，
 * 1.0 又会导致后续 watch 一直报 dirty。这里手动 parse + clamp，UI 层面更稳。
 */
const onZoomInput = (slot: 0 | 1, event: Event) => {
  const target = event.target as HTMLInputElement
  const parsed = parseFloat(target.value)
  form.value.slots[slot].zoom = clampZoom(parsed)
}

/** 弹窗内即时夹紧，保证控件值始终在合法区间，避免用户输入 NaN / 0 / 负值。 */
const clampZoom = (z: number): number => {
  if (typeof z !== 'number' || !Number.isFinite(z)) return 1.0
  if (z < 1.0) return 1.0
  if (z > ZOOM_INPUT_MAX) return ZOOM_INPUT_MAX
  return Math.round(z * 10) / 10
}

const loadConfig = async () => {
  loading.value = true
  errorMsg.value = ''
  try {
    const cfg = await DualCamera.getCaptureConfig()
    form.value.slots = slotsToTuple(cfg.slots)
    snapshot.value = JSON.stringify(form.value)
    isDirty.value = false
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      successMsg.value = ''
      loadConfig()
    }
  },
)

watch(
  form,
  () => {
    isDirty.value = JSON.stringify(form.value) !== snapshot.value
  },
  { deep: true },
)

const handleSave = async () => {
  saving.value = true
  errorMsg.value = ''
  successMsg.value = ''
  try {
    const updated = await DualCamera.setCaptureConfig({ slots: form.value.slots })
    form.value.slots = slotsToTuple(updated.slots)
    snapshot.value = JSON.stringify(form.value)
    isDirty.value = false
    successMsg.value = '已保存。下次进入拍照预览生效。'
  } catch (err) {
    errorMsg.value = err instanceof Error ? err.message : String(err)
  } finally {
    saving.value = false
  }
}

const handleClose = () => {
  if (isDirty.value) {
    if (!window.confirm('有未保存的修改，确定关闭？')) return
  }
  emit('close')
}

const askExit = () => {
  showExitConfirm.value = true
}

const handleExitConfirmed = async () => {
  showExitConfirm.value = false
  try {
    // 直接走 QuitApp，保持和首页一致的退出路径。
    const { QuitApp } = await import('@/plugins/quit-app')
    await QuitApp.exitApp()
  } catch (err) {
    errorMsg.value = err instanceof Error ? `退出失败: ${err.message}` : String(err)
  }
}

const handleExitCancelled = () => {
  showExitConfirm.value = false
}
</script>

<template>
  <Teleport to="body">
    <Transition name="dev-fade">
      <div v-if="visible" class="dev-overlay" role="dialog" aria-modal="true">
        <div class="dev-card">
          <header class="dev-header">
            <h2 class="dev-title">开发者工具</h2>
            <button class="dev-close" type="button" aria-label="关闭" @click="handleClose">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <path
                  d="M18 6L6 18M6 6l12 12"
                  stroke="#BCBCBC"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </header>

          <section class="dev-body">
            <div class="section-title">
              <span>拍照方向校准</span>
              <span v-if="loading" class="status-text">读取中…</span>
              <span v-else-if="isDirty" class="status-text dirty">有未保存修改</span>
            </div>
            <p class="section-hint">保存后写入 native JSON 配置；下次进入拍照预览时生效。</p>

            <div class="slot-grid">
              <fieldset class="slot-block">
                <legend>左预览（slot 0 / preview）</legend>
                <label class="select-row">
                  <span>旋转角度</span>
                  <select v-model.number="form.slots[0].previewRotation" :disabled="loading || saving">
                    <option v-for="r in ROTATION_OPTIONS" :key="r" :value="r">{{ r }}°</option>
                  </select>
                </label>
                <label class="check-row">
                  <input
                    type="checkbox"
                    v-model="form.slots[0].mirror"
                    :disabled="loading || saving"
                  />
                  <span>水平镜像</span>
                </label>
                <label class="select-row">
                  <span>数字缩放</span>
                  <input
                    type="number"
                    class="zoom-input"
                    :min="1"
                    :max="ZOOM_INPUT_MAX"
                    :step="0.1"
                    :value="form.slots[0].zoom"
                    :disabled="loading || saving"
                    @input="onZoomInput(0, $event)"
                  />
                  <span class="zoom-suffix">×</span>
                </label>
              </fieldset>

              <fieldset class="slot-block">
                <legend>右预览（slot 1 / preview）</legend>
                <label class="select-row">
                  <span>旋转角度</span>
                  <select v-model.number="form.slots[1].previewRotation" :disabled="loading || saving">
                    <option v-for="r in ROTATION_OPTIONS" :key="r" :value="r">{{ r }}°</option>
                  </select>
                </label>
                <label class="check-row">
                  <input
                    type="checkbox"
                    v-model="form.slots[1].mirror"
                    :disabled="loading || saving"
                  />
                  <span>水平镜像</span>
                </label>
                <label class="select-row">
                  <span>数字缩放</span>
                  <input
                    type="number"
                    class="zoom-input"
                    :min="1"
                    :max="ZOOM_INPUT_MAX"
                    :step="0.1"
                    :value="form.slots[1].zoom"
                    :disabled="loading || saving"
                    @input="onZoomInput(1, $event)"
                  />
                  <span class="zoom-suffix">×</span>
                </label>
              </fieldset>

              <fieldset class="slot-block">
                <legend>左拍照（slot 0 / capture）</legend>
                <label class="select-row">
                  <span>旋转角度</span>
                  <select v-model.number="form.slots[0].captureRotation" :disabled="loading || saving">
                    <option v-for="r in ROTATION_OPTIONS" :key="r" :value="r">{{ r }}°</option>
                  </select>
                </label>
              </fieldset>

              <fieldset class="slot-block">
                <legend>右拍照（slot 1 / capture）</legend>
                <label class="select-row">
                  <span>旋转角度</span>
                  <select v-model.number="form.slots[1].captureRotation" :disabled="loading || saving">
                    <option v-for="r in ROTATION_OPTIONS" :key="r" :value="r">{{ r }}°</option>
                  </select>
                </label>
              </fieldset>
            </div>

            <p v-if="errorMsg" class="msg msg-error">{{ errorMsg }}</p>
            <p v-else-if="successMsg" class="msg msg-success">{{ successMsg }}</p>
          </section>

          <footer class="dev-footer">
            <button class="btn-secondary" type="button" :disabled="saving" @click="handleClose">
              关闭
            </button>
            <button
              class="btn-primary"
              type="button"
              :disabled="loading || saving || !isDirty"
              @click="handleSave"
            >
              {{ saving ? '保存中…' : '保存配置' }}
            </button>
            <button class="btn-danger" type="button" :disabled="saving" @click="askExit">
              退出应用
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>

  <ConfirmDialog
    :visible="showExitConfirm"
    title="退出应用"
    message="确定要退出应用吗？"
    confirm-text="退出"
    cancel-text="取消"
    confirm-color="#e74c3c"
    @confirm="handleExitConfirmed"
    @cancel="handleExitCancelled"
    @close="handleExitCancelled"
  />
</template>

<style scoped lang="scss">
.dev-overlay {
  position: fixed;
  inset: 0;
  z-index: 1100;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.dev-card {
  width: 100%;
  max-width: 720px;
  max-height: 90vh;
  background: #ffffff;
  border-radius: 56px;
  padding: 56px 56px 40px;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dev-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.dev-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 38px;
  font-weight: 700;
  color: #000;
  margin: 0;
}

.dev-close {
  width: 56px;
  height: 56px;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: background-color 0.2s;

  &:hover {
    background: #f5f5f5;
  }

  &:active {
    background: #ebebeb;
  }
}

.dev-body {
  flex: 1;
  overflow-y: auto;
  margin-bottom: 24px;
  padding-right: 8px;
}

.section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 26px;
  font-weight: 600;
  color: #000;
  margin-bottom: 8px;
}

.status-text {
  font-size: 18px;
  font-weight: 400;
  color: #999;
}

.status-text.dirty {
  color: #ff9900;
}

.section-hint {
  font-size: 18px;
  color: #666;
  margin: 0 0 20px;
  line-height: 1.5;
}

.slot-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.slot-block {
  border: 1px solid #e6e6e6;
  border-radius: 24px;
  padding: 16px 20px;
  margin: 0;

  legend {
    font-size: 20px;
    font-weight: 600;
    color: #333;
    padding: 0 8px;
  }
}

.select-row,
.check-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 22px;
  margin-top: 12px;
}

.check-row {
  cursor: pointer;

  span {
    margin-left: 8px;
  }
}

.select-row select {
  height: 48px;
  border-radius: 12px;
  border: 1px solid #d9d9d9;
  font-size: 22px;
  padding: 0 12px;
  background: #fff;
  min-width: 96px;
}

.select-row .zoom-input {
  height: 48px;
  width: 96px;
  border-radius: 12px;
  border: 1px solid #d9d9d9;
  font-size: 22px;
  padding: 0 12px;
  background: #fff;
  text-align: right;
  // 隐藏 number input 默认的上下小箭头，移动端可按住键盘完成调节
  -moz-appearance: textfield;
  appearance: textfield;
  &::-webkit-outer-spin-button,
  &::-webkit-inner-spin-button {
    -webkit-appearance: none;
    margin: 0;
  }
}

.zoom-suffix {
  min-width: 20px;
  margin-left: 8px;
  font-size: 22px;
  color: #666;
}

.check-row input[type='checkbox'] {
  width: 28px;
  height: 28px;
}

.msg {
  margin-top: 16px;
  font-size: 18px;
  line-height: 1.5;
  padding: 8px 12px;
  border-radius: 12px;
}

.msg-error {
  color: #b00020;
  background: #fdecef;
}

.msg-success {
  color: #1a7f37;
  background: #e6f6ec;
}

.dev-footer {
  display: flex;
  gap: 16px;
  flex-shrink: 0;
}

.dev-footer button {
  flex: 1;
  height: 76px;
  border-radius: 50px;
  font-size: 28px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
  border: none;

  &:active:not(:disabled) {
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.btn-secondary {
  background: transparent;
  border: 2px solid #d9d9d9;
  color: #000;

  &:hover:not(:disabled) {
    border-color: #bebebe;
    background: #fafafa;
  }
}

.btn-primary {
  background: #ff9900;
  color: #fff;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);

  &:hover:not(:disabled) {
    filter: brightness(0.95);
  }
}

.btn-danger {
  background: #e74c3c;
  color: #fff;
  box-shadow: 0 8px 24px rgba(231, 76, 60, 0.35);

  &:hover:not(:disabled) {
    filter: brightness(0.95);
  }
}

.dev-fade-enter-active,
.dev-fade-leave-active {
  transition: opacity 0.25s ease;

  .dev-card {
    transition:
      transform 0.25s ease,
      opacity 0.25s ease;
  }
}

.dev-fade-enter-from,
.dev-fade-leave-to {
  opacity: 0;

  .dev-card {
    transform: scale(0.9);
    opacity: 0;
  }
}
</style>
