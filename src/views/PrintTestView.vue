<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
// path-current: 单一路径打印入口。不再使用 fitImageToPaper letterbox，
// 直接走 JS canvas → base64 → native fast path (1844×1240 缩放)。
// path-current: HiTi init/release 由 router.beforeEach 守卫统一管理
// （见 src/router/index.ts），进入 /print-test 自动 initForPage (path-current)，
// 离开到任意其它路由自动 releaseForPage。不再在 page-level 手动调 printPageMounted。
import { printPhoto } from '@/utils/print-current'
import { HiTiPrinterCurrent } from '@/plugins/hiti-printer-current'
// Vite 的 `?url` 拿 URL；`?inline` 直接拿 base64 dataURL（编译期内联）。
import analysisSuccess from '@/assets/images/analysis-success.png?inline'

const router = useRouter()
const goBack = () => router.push('/')

// 单次打印入口（与 ScanSubscription 一致）
const isComposing = ref(false)
const isPrinting = ref(false)
const hasPrinted = ref(false)
const printError = ref('')

/**
 * 调试用：合成的最终打印图预览（data:image/jpeg;base64,...），
 * 仅在 previewVisible = true 时展示。点击"打印"按钮后会先填这两个 ref 打开弹窗，
 * 用户点"确认打印"才真正调用 printPhoto 把图送到 HiTi。
 */
const previewVisible = ref(false)
const previewDataUrl = ref('')
/** "确认打印" 时实际 printPhoto 需要的 base64（保持与预览时输入一致）。 */
const pendingPrintBase64 = ref('')

// （已移除 onMounted / onBeforeUnmount / onUnmounted 的 printPageMounted 调用 ——
//  改由 router.beforeEach 统一管理 HiTi USB 占用周期。）

const pickedImgUrl = ref<string>('')
const pickedImgName = ref<string>('')
const fileInputRef = ref<HTMLInputElement | null>(null)

function onPickClick() {
  if (fileInputRef.value) fileInputRef.value.value = ''
  fileInputRef.value?.click()
}

function onPickChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  pickedImgName.value = file.name
  printError.value = ''
  const reader = new FileReader()
  reader.onload = () => {
    const result = typeof reader.result === 'string' ? reader.result : ''
    pickedImgUrl.value = result
    console.log('[PrintTest/current] picked image', {
      name: file.name,
      type: file.type,
      size: file.size,
      dataUrlPrefix: result.slice(0, 40),
      dataUrlLen: result.length,
    })
  }
  reader.onerror = () => {
    printError.value = '读取本地图片失败'
    console.error('[PrintTest/current] FileReader error:', reader.error)
  }
  reader.readAsDataURL(file)
}

function onClearPicked() {
  pickedImgUrl.value = ''
  pickedImgName.value = ''
  hasPrinted.value = false
  if (fileInputRef.value) fileInputRef.value.value = ''
}

// path-current: 单一路径，"先预览后打印"。
// 流程：onPrint → composePrintOverlay 拿到合成图 base64 → 弹窗显示 → 用户确认 → 真正 printPhoto
async function onPrint() {
  if (isComposing.value || isPrinting.value || hasPrinted.value) return
  const imgUrl = pickedImgUrl.value || analysisSuccess
  isComposing.value = true
  printError.value = ''
  console.log('[PrintTest/current] onPrint: step 1/2 composePrintOverlay', {
    source: pickedImgUrl.value ? 'picked' : 'builtin',
    imgName: pickedImgName.value || '(builtin analysis-success.png)',
    imgUrlType: imgUrl.slice(0, 40),
    len: imgUrl.length,
  })
  try {
    // 取出主图 base64（去掉 data:image/...;base64, 前缀）
    const comma = imgUrl.indexOf(',')
    const mainBase64 = comma >= 0 ? imgUrl.slice(comma + 1) : imgUrl
    if (!mainBase64) throw new Error('主图 base64 解析为空')

    const r = await HiTiPrinterCurrent.composePrintOverlay({
      base64: mainBase64,
      paperType: 2, // 4×6 portrait 1240×1844（设计稿 100×150mm 竖版；HiTi SDK PaperSize_6X4_SPLIT_2UP）
    })
    if (!r.ok || !r.data?.base64) {
      throw new Error(`合成预览失败：${r.ok ? '返回数据为空' : r.error}`)
    }
    previewDataUrl.value = `data:${r.data.contentType};base64,${r.data.base64}`
    pendingPrintBase64.value = mainBase64
    previewVisible.value = true
    console.log('[PrintTest/current] onPrint: step 1/2 done, preview ready', {
      previewLen: previewDataUrl.value.length,
      paperType: r.data.paperType,
    })
  } catch (e) {
    const err = e as Error
    console.error('[PrintTest/current] composePrintOverlay rejected:', err)
    printError.value = err?.message ?? '合成预览失败'
  } finally {
    isComposing.value = false
  }
}

async function onConfirmPrint() {
  if (isPrinting.value || hasPrinted.value) return
  if (!pendingPrintBase64.value) {
    previewVisible.value = false
    return
  }
  previewVisible.value = false
  isPrinting.value = true
  printError.value = ''
  // 把 base64 重新拼回 dataURL 给 printPhoto（utils/print-current 入口接受 goodImgUrl）
  const goodImgUrl = `data:image/jpeg;base64,${pendingPrintBase64.value}`
  console.log('[PrintTest/current] onConfirmPrint: step 2/2 printPhoto')
  try {
    await printPhoto({
      goodImgUrl,
      jobName: pickedImgName.value
        ? `本地打印-${pickedImgName.value}`
        : `打印测试照片[current]`,
    })
    hasPrinted.value = true
    console.log('[PrintTest/current] printPhoto resolved (success)')
  } catch (e) {
    const err = e as Error
    console.error('[PrintTest/current] printPhoto rejected:', err)
    printError.value = err?.message ?? '打印失败'
  } finally {
    isPrinting.value = false
    pendingPrintBase64.value = ''
  }
}

function onCancelPreview() {
  previewVisible.value = false
  previewDataUrl.value = ''
  pendingPrintBase64.value = ''
  console.log('[PrintTest/current] preview cancelled')
}
</script>

<template>
  <div class="print-test-page">
    <button class="back-btn" @click="goBack">← 返回首页</button>

    <h1 class="title">打印功能验证</h1>
    <p class="subtitle">调用入口与详情页 ScanSubscription 完全一致</p>

    <!-- 本地选图区域 -->
    <div class="picker-block">
      <input
        ref="fileInputRef"
        type="file"
        accept="image/*"
        class="file-input-hidden"
        @change="onPickChange"
      />

      <div class="picker-row">
        <button
          class="pick-btn"
          type="button"
          :disabled="isPrinting || hasPrinted"
          @click="onPickClick"
        >
          📁 从本地选择图片
        </button>
        <button
          v-if="pickedImgUrl"
          class="clear-btn"
          type="button"
          :disabled="isPrinting || hasPrinted"
          @click="onClearPicked"
        >
          ✕ 清空
        </button>
      </div>

      <div v-if="pickedImgUrl" class="picked-preview">
        <img :src="pickedImgUrl" alt="picked" />
        <div class="picked-meta">
          <div class="picked-name">📎 {{ pickedImgName }}</div>
          <div class="picked-hint">将使用本图打印</div>
        </div>
      </div>
      <div v-else class="picked-hint muted">
        未选图时将使用内置测试图 analysis-success.png
      </div>
    </div>

    <!-- path-current: 单一按钮，点击先合成预览，弹窗内二次确认后再真打印 -->
    <div class="mode-block">
      <div class="mode-title">path-current 单路径打印（先预览后打印）</div>
      <div class="mode-grid">
        <button
          type="button"
          class="mode-btn mode-btn-single"
          :disabled="isComposing || isPrinting || hasPrinted"
          @click="onPrint()"
        >
          <span class="mode-btn-label">{{ isComposing ? '正在合成预览…' : '打印' }}</span>
        </button>
      </div>
    </div>

    <p v-if="printError" class="print-error">{{ printError }}</p>

    <!-- 调试用预览弹窗：显示合成后的最终打印图（不送 SDK），用户确认后才真正打印 -->
    <div v-if="previewVisible" class="preview-overlay" @click.self="onCancelPreview">
      <div class="preview-card" role="dialog" aria-modal="true" aria-label="打印合成预览">
        <h2 class="preview-title">打印合成预览</h2>
        <p class="preview-sub">下方为最终送进打印机的合成图，请确认效果后点击"确认打印"</p>
        <div class="preview-img-wrap">
          <img v-if="previewDataUrl" :src="previewDataUrl" alt="合成预览" class="preview-img" />
        </div>
        <div class="preview-actions">
          <button
            type="button"
            class="preview-btn preview-btn-cancel"
            :disabled="isPrinting"
            @click="onCancelPreview"
          >
            取消
          </button>
          <button
            type="button"
            class="preview-btn preview-btn-confirm"
            :disabled="isPrinting || !pendingPrintBase64"
            @click="onConfirmPrint"
          >
            {{ isPrinting ? '正在打印…' : '确认打印' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.print-test-page {
  height: 100vh;
  position: relative;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  overflow: auto;
  background: #f5f5f7;
  padding: 40px;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    'PingFang SC',
    sans-serif;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24px;
}

.back-btn {
  align-self: flex-start;
  background: rgba(0, 0, 0, 0.05);
  border: none;
  padding: 10px 20px;
  border-radius: 12px;
  font-size: 24px;
  cursor: pointer;

  &:active {
    background: rgba(0, 0, 0, 0.1);
  }
}

.title {
  font-size: 56px;
  font-weight: 700;
  color: #111;
  margin: 16px 0 0;
  text-align: center;
}

.subtitle {
  font-size: 22px;
  color: #666;
  margin: 0;
  text-align: center;
}

.picker-block {
  width: 425px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  background: #fff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.file-input-hidden {
  display: none;
}

.picker-row {
  display: flex;
  gap: 12px;
}

.pick-btn {
  flex: 1;
  height: 72px;
  background: #f0f0f3;
  color: #222;
  border: 1px dashed #aaa;
  border-radius: 12px;
  font-size: 22px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s ease;

  &:active:not(:disabled) {
    background: #e5e5ea;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.clear-btn {
  width: 100px;
  height: 72px;
  background: #fff;
  color: #888;
  border: 1px solid #ddd;
  border-radius: 12px;
  font-size: 22px;
  cursor: pointer;

  &:active:not(:disabled) {
    background: #f5f5f7;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.picked-preview {
  display: flex;
  gap: 16px;
  align-items: center;

  img {
    width: 140px;
    height: 140px;
    object-fit: cover;
    border-radius: 12px;
    border: 1px solid #eee;
    background: #fafafa;
  }
}

.picked-meta {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.picked-name {
  font-size: 20px;
  font-weight: 600;
  color: #222;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.picked-hint {
  font-size: 16px;
  color: #2a8a3e;

  &.muted {
    color: #888;
    text-align: center;
    padding: 8px 0;
  }
}

.print-error {
  font-size: 18px;
  color: #ff4d4f;
  text-align: center;
}

.mode-block {
  width: 425px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 20px;
  border: 1px solid #ddd;
  border-radius: 12px;
  background: #fff;
}

.mode-title {
  font-size: 20px;
  font-weight: 600;
  color: #111;
}

.mode-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.mode-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 4px;
  padding: 14px 16px;
  background: #ff9900;
  color: #fff;
  border: none;
  border-radius: 12px;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition:
    transform 0.2s ease,
    opacity 0.2s ease,
    background 0.15s ease;
  min-height: 64px;

  &:active:not(:disabled) {
    transform: scale(0.98);
    background: #e68a00;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.mode-btn-label {
  font-size: 20px;
  font-weight: 600;
  color: #222;
  white-space: nowrap;
}

/**
 * 打印合成预览弹窗：调试用，确认效果用。
 * 全屏半透明遮罩 + 中央白卡 + 图片预览 + 取消/确认按钮。
 */
.preview-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 9999;
  padding: 24px;
}

.preview-card {
  background: #fff;
  border-radius: 20px;
  padding: 24px 28px;
  width: min(720px, 100%);
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.25);
}

.preview-title {
  font-size: 26px;
  font-weight: 700;
  margin: 0;
  color: #111;
}

.preview-sub {
  font-size: 16px;
  color: #555;
  margin: 0;
}

.preview-img-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f3f3f5;
  border-radius: 12px;
  padding: 12px;
  overflow: auto;
}

.preview-img {
  /* 4×6 landscape 合成图：max-height ~60vh 防止弹出屏幕 */
  max-width: 100%;
  max-height: 60vh;
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
  background: #fff;
  display: block;
}

.preview-actions {
  display: flex;
  gap: 12px;
  justify-content: flex-end;
}

.preview-btn {
  height: 56px;
  min-width: 140px;
  padding: 0 28px;
  border-radius: 12px;
  border: none;
  font-size: 20px;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    opacity 0.15s ease,
    background 0.15s ease;
}

.preview-btn:active:not(:disabled) {
  transform: scale(0.98);
}

.preview-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.preview-btn-cancel {
  background: #f0f0f3;
  color: #222;
}

.preview-btn-cancel:hover:not(:disabled) {
  background: #e5e5ea;
}

.preview-btn-confirm {
  background: #ff9900;
  color: #fff;
}

.preview-btn-confirm:hover:not(:disabled) {
  background: #e68a00;
}

</style>
