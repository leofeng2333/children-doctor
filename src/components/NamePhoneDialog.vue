<script setup lang="ts">
/**
 * NamePhoneDialog — 姓名 + 手机号输入弹窗
 *
 * <p>来源：{@code h5-oral-checkup/src/pages/NamePhonePage.tsx}（口腔-面容变化 H5
 * 项目的 first 模式输入页），主项目弹窗版。
 *
 * <p>改动要点：
 * <ul>
 *   <li>React 页面 → Vue 3 弹窗组件（Teleport + Transition + 卡片式覆盖层）</li>
 *   <li>尺寸单位 vw → px：按 H5 设计稿基准 807px 等比换算（{@code N vw → N × 8.07 px}）</li>
 *   <li>导航逻辑从组件内移除：通过 {@code submit} 事件把 {@code {name, phone}} 抛给父组件，
 *       由父组件决定后续跳转 / 接口调用（对齐 PasswordDialog 的 emit 风格）</li>
 *   <li>react-router useLocation → 通过 props 注入 {@code llmAnalysisId}（弹窗不读 URL）</li>
 *   <li>H5 useToast → 主项目 {@code @capacitor/toast}（保持原"输入校验失败 → toast"语义）</li>
 *   <li>保留 H5 视觉：橙色卡背景 + 黄按钮 + 白色圆角输入 + 顶部装饰图 + 底部白色提示文案</li>
 * </ul>
 */
import { ref, computed, nextTick } from 'vue'
import { Toast } from '@capacitor/toast'
import { isValidPhone, normalizePhone } from '@/utils/phoneValidation'

const props = defineProps<{
  visible: boolean
  /** LLM 任务 id（来自路由 / 父组件注入），提交时一并抛给父组件 */
  llmAnalysisId?: string
  /**
   * 公众号二维码图片 URL。父组件在 `bindNamePhone` + `getWxQrcode`
   * 调用成功后将此 prop 置为非空字符串，弹窗切换到二维码视图。
   */
  qrcodeUrl?: string
  /**
   * 父组件正在调"绑定 + 取二维码"接口。弹窗切换到 loading 视图并禁用
   * 关闭 / 提交按钮。
   */
  loading?: boolean
}>()

const emit = defineEmits<{
  close: []
  /** 校验通过后抛给父组件，父组件决定后续接口调用 + 跳转 */
  submit: [{ name: string; phone: string; llmAnalysisId: string }]
}>()

const name = ref('')
const phone = ref('')
const submitting = ref(false)

/**
 * 弹窗当前所处的视图阶段：
 *   - 'input'   默认表单视图（姓名 + 手机号）
 *   - 'qrcode'  绑定完成，展示公众号二维码
 * 接口调用中（绑定 / 取二维码）不再切换独立视图，而是通过提交按钮的 loading 态展示。
 */
type DialogPhase = 'input' | 'qrcode'
const phase = computed<DialogPhase>(() => {
  if (props.qrcodeUrl && props.qrcodeUrl.length > 0) return 'qrcode'
  return 'input'
})

const nameInputRef = ref<HTMLInputElement | null>(null)
const phoneInputRef = ref<HTMLInputElement | null>(null)

async function focusName() {
  await nextTick()
  nameInputRef.value?.focus()
}

async function focusPhone() {
  await nextTick()
  phoneInputRef.value?.focus()
}

async function handleSubmit() {
  if (submitting.value) return

  const n = name.value.trim()
  const p = normalizePhone(phone.value.trim())
  if (!n) {
    await focusName()
    await Toast.show({ text: '请输入姓名', position: 'center', duration: 'short' })
    return
  }
  if (!isValidPhone(p)) {
    await focusPhone()
    await Toast.show({ text: '请输入正确的手机号', position: 'center', duration: 'short' })
    return
  }

  const llmAnalysisId = (props.llmAnalysisId ?? '').trim()
  if (!llmAnalysisId) {
    await Toast.show({
      text: '报告链接无效，请重新扫码',
      position: 'center',
      duration: 'short',
    })
    return
  }

  submitting.value = true
  try {
    emit('submit', { name: n, phone: p, llmAnalysisId })
  } finally {
    // 父组件 resolve 后再解锁；这里保守先不解锁，由父组件通过 close 事件清理
  }
}

function handleClose() {
  reset()
  emit('close')
}

function reset() {
  name.value = ''
  phone.value = ''
  submitting.value = false
}

defineExpose({
  /** 父组件拿到结果后调用，关闭弹窗并重置表单 */
  closeWithResult() {
    reset()
    emit('close')
  },
  /** 父组件请求失败时调用，解锁提交按钮 */
  unlockSubmit() {
    submitting.value = false
  },
})
</script>

<template>
  <Teleport to="body">
    <Transition name="dialog-fade">
      <div v-if="visible" class="npd-overlay" role="dialog" aria-modal="true">
        <div class="npd-card">
          <!-- 3 个背景装饰圆（H5 page 风格） -->
          <!-- <span class="npd-bg-deco npd-bg-deco-d1" aria-hidden="true" />
          <span class="npd-bg-deco npd-bg-deco-d2" aria-hidden="true" />
          <span class="npd-bg-deco npd-bg-deco-d3" aria-hidden="true" /> -->

          <!-- 关闭按钮 -->
          <button class="npd-close" type="button" aria-label="关闭" @click="handleClose">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M18 6L6 18M6 6l12 12" stroke="#000000" stroke-width="2" stroke-linecap="round"
                stroke-linejoin="round" />
            </svg>
          </button>

          <div class="npd-content">
            <!-- 头部装饰图：仅 input 阶段展示，二维码阶段隐藏 -->
            <div v-if="phase === 'input'" class="npd-header-illu" aria-hidden="true">
              <span class="npd-header-illu-icon" />
            </div>

            <!-- 阶段 1：输入表单 -->
            <template v-if="phase === 'input'">
              <h1 class="npd-title">
                填写手机号
                <br />
                可在公众号中永久查看报告
              </h1>

              <form class="npd-form" @submit.prevent="handleSubmit">
                <div class="npd-field-group">
                  <label class="npd-field-label" for="npd-name">我该怎么称呼你呢？</label>
                  <div class="npd-field">
                    <input id="npd-name" ref="nameInputRef" v-model="name" type="text" maxlength="40" placeholder="输入姓名"
                      autocomplete="name" />
                  </div>
                </div>

                <div class="npd-field-group">
                  <label class="npd-field-label" for="npd-phone">你的联系方式？</label>
                  <div class="npd-field">
                    <input id="npd-phone" ref="phoneInputRef" v-model="phone" type="tel" inputmode="numeric"
                      maxlength="11" placeholder="输入手机号" autocomplete="tel" />
                  </div>
                </div>

                <button type="submit" class="npd-view-photos" :disabled="loading || submitting"
                  :class="{ 'is-loading': loading || submitting }">
                  <span v-if="loading || submitting" class="npd-btn-spinner" aria-hidden="true" />
                  查看报告
                </button>

                <p class="npd-tips">* 请记住所填写的手机号，方便下次查看电子诊断结果时使用哦！</p>
              </form>
            </template>

            <!-- 阶段 2：展示公众号二维码 -->
            <template v-else>
              <div class="npd-qrcode">
                <div class="npd-qrcode-frame">
                  <img class="npd-qrcode-img" :src="qrcodeUrl" alt="公众号二维码" />
                </div>
                <p class="npd-tips">
                  扫一扫关注公众号，永久获取电子版照片
                </p>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped lang="scss">
/*
 * 单位换算参考：
 *   H5 设计稿基准 807px → 1vw = 8.07px
 *   例：8.55vw ≈ 69px, 5.95vw ≈ 48px, 83.02vw ≈ 670px
 * 卡片宽度对齐 H5 设计稿 807px，对应 8.55vw 左右内边距 → 内容区 670px，
 * 输入框 / 按钮等宽度刚好 670px 整版还原 H5 视觉。
 */

.npd-overlay {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.npd-card {
  position: relative;
  width: 100%;
  max-width: 790px; // 还原 H5 设计稿宽
  background: #f2684e; // H5 --page-bg
  border-radius: 56px;
  padding: 280px 60px 60px; // H5: 40.16vw 8.55vw 0 → 324/69/0
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.25);
  box-sizing: border-box;
  overflow: hidden;
  min-height: 880px; // H5 page min-height ≈ 100dvh ≈ 880px @ 807w × 16:9
}

// 背景装饰：3 个粉色块，模拟 H5 Figma 的 Vector 5/6/7
.npd-bg-deco {
  position: absolute;
  pointer-events: none;
  background: #f2a4a2; // H5 --deco-bg
  z-index: 0;
}

.npd-bg-deco-d1 {
  top: -32px; // H5: -3.96 * 8.07 ≈ -32px
  left: 365px; // H5: 45.23 * 8.07 ≈ 365px
  width: 537px; // H5: 66.6 * 8.07 ≈ 537px
  height: 575px; // H5: 71.25 * 8.07 ≈ 575px
  border-radius: 50% 50% 45% 55% / 60% 40% 60% 40%;
}

.npd-bg-deco-d2 {
  top: 839px; // H5: 103.97 * 8.07 ≈ 839px
  left: -262px; // H5: -32.42 * 8.07 ≈ -262px
  width: 925px; // H5: 114.72 * 8.07 ≈ 925px
  height: 972px; // H5: 120.5 * 8.07 ≈ 972px
  border-radius: 60% 40% 55% 45% / 50% 50% 50% 50%;
}

.npd-bg-deco-d3 {
  top: 2244px; // H5: 278 * 8.07 ≈ 2244px
  left: -14px; // H5: -1.74 * 8.07 ≈ -14px
  width: 1175px; // H5: 145.57 * 8.07 ≈ 1175px
  height: 1167px; // H5: 144.7 * 8.07 ≈ 1167px
  border-radius: 40% 60% 50% 50% / 50% 50% 60% 40%;
}

.npd-close {
  position: absolute;
  top: 32px;
  right: 32px;
  width: 56px;
  height: 56px;
  background: #fff;
  border: none;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  z-index: 3;
  transition: background-color 0.2s;

  &:hover:not(:disabled) {
    background: rgba(0, 0, 0, 0.32);
  }

  &:active:not(:disabled) {
    transform: scale(0.96);
  }

  &:disabled {
    cursor: not-allowed;
    opacity: 0.45;
  }
}

.npd-content {
  position: relative;
  z-index: 1;
}

// 头部装饰图（H5 .header-illu）
.npd-header-illu {
  position: absolute;
  top: -260px; // H5: 10.4vw ≈ 84px，减去 card padding-top(324) → -240px 偏上露出
  left: -20px; // H5: 5.58vw ≈ 45px
  width: 286px; // H5: 35.44vw ≈ 286px
  height: 247px; // H5: 30.6vw ≈ 247px
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.npd-header-illu-icon {
  width: 85%;
  height: 80%;
  background-image: url('../assets/images/html-icon.png');
  background-repeat: no-repeat;
  background-size: 700% 400%;
  background-position: 80% 45%;
}

// 标题
.npd-title {
  font-size: 48px; // H5: 5.95vw ≈ 48px
  font-weight: 700;
  line-height: 1.35;
  color: #ffffff;
  margin: 0 0 60px 0; // H5 margin-bottom: 9.5vw ≈ 77px
}

// 表单
.npd-form {
  display: flex;
  flex-direction: column;
  gap: 32px; // H5: 4vw ≈ 32px
}

.npd-field-group {
  display: flex;
  flex-direction: column;
  gap: 16px; // H5: 2vw ≈ 16px
}

.npd-field-label {
  font-size: 32px; // H5: 3.96vw ≈ 32px
  font-weight: 700;
  color: #ffffff;
  padding: 0 8px; // H5: 0 1vw ≈ 8px
}

.npd-field {
  position: relative;
  width: 100%; // 填满 670px 内容区
  height: 72px; // H5: 8.92vw ≈ 72px
  background: #ffffff;
  border-radius: 36px; // H5: 4.46vw ≈ 36px
  display: flex;
  align-items: center;
  padding: 0 36px; // H5: 0 4.46vw ≈ 36px
  color: #d9d9d9;
  font-size: 20px; // H5: 2.47vw ≈ 20px
  font-weight: 700;

  input {
    flex: 1;
    border: none;
    outline: none;
    background: transparent;
    color: #000000;
    font: inherit;

    &::placeholder {
      font-size: 20px; // H5: 2.47vw ≈ 20px
      color: #d9d9d9;
    }
  }
}

// 查看报告按钮
.npd-view-photos {
  width: 100%;
  height: 72px; // H5: 8.92vw ≈ 72px
  margin-top: 32px; // H5: 4vw ≈ 32px
  background: #ffe361;
  border: none;
  border-radius: 36px; // H5: 4.46vw ≈ 36px
  color: #000000;
  font-size: 26px; // H5: 3.2vw ≈ 26px
  font-weight: 700;
  letter-spacing: 2px; // H5: 0.2vw ≈ 1.6 → 2px
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s, box-shadow 0.15s;

  &:active:not(:disabled) {
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.7;
    cursor: not-allowed;
  }

  &.is-loading {
    opacity: 0.85;
  }
}

.npd-btn-spinner {
  display: inline-block;
  width: 22px;
  height: 22px;
  border: 3px solid rgba(0, 0, 0, 0.25);
  border-top-color: #000000;
  border-radius: 50%;
  margin-right: 12px;
  animation: npd-spin 0.7s linear infinite;
}

@keyframes npd-spin {
  to {
    transform: rotate(360deg);
  }
}

// 提示文案
.npd-tips {
  margin: 4px 0 0 0; // H5: 0.5vw ≈ 4px
  color: #ffffff;
  font-size: 24px; // H5: 2.8vw ≈ 23px
  font-weight: 700;
  line-height: 1.5;
  text-align: center;
  // opacity: 0.85;
}

// 阶段 2：loading 视图
.npd-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 32px;
  min-height: 520px;
}

.npd-spinner {
  width: 72px;
  height: 72px;
  border: 6px solid rgba(255, 255, 255, 0.25);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: npd-spin 0.8s linear infinite;
}

.npd-loading-text {
  margin: 0;
  color: #ffffff;
  font-size: 28px;
  font-weight: 700;
}

@keyframes npd-spin {
  to {
    transform: rotate(360deg);
  }
}

// 阶段 3：二维码视图 —— 居中展示
.npd-qrcode {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 40px;

  .npd-title {
    margin: 0;
    text-align: center;
  }
}

.npd-qrcode-frame {
  width: 320px;
  height: 320px;
  background: #ffffff;
  border-radius: 24px;
  padding: 20px;
  box-sizing: border-box;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  display: flex;
  align-items: center;
  justify-content: center;
}

.npd-qrcode-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

// 过渡动画（对齐 PasswordDialog 的 dialog-fade）
.dialog-fade-enter-active,
.dialog-fade-leave-active {
  transition: opacity 0.25s ease;

  .npd-card {
    transition:
      transform 0.25s ease,
      opacity 0.25s ease;
  }
}

.dialog-fade-enter-from,
.dialog-fade-leave-to {
  opacity: 0;

  .npd-card {
    transform: scale(0.92);
    opacity: 0;
  }
}
</style>