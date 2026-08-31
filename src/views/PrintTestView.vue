<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
// path-current: 单一路径打印入口。不再使用 fitImageToPaper letterbox，
// 直接走 JS canvas → base64 → native fast path (1844×1240 缩放)。
// path-current: HiTi init/release 由 router.beforeEach 守卫统一管理
// （见 src/router/index.ts），进入 /print-test 自动 initForPage (path-current)，
// 离开到任意其它路由自动 releaseForPage。不再在 page-level 手动调 printPageMounted。
import { printPhoto } from '@/utils/print-current'
// Vite 的 `?url` 拿 URL；`?inline` 直接拿 base64 dataURL（编译期内联）。
import analysisSuccess from '@/assets/images/analysis-success.png?inline'

const router = useRouter()
const goBack = () => router.push('/')

// 单次打印入口（与 ScanSubscription 一致）
const isPrinting = ref(false)
const hasPrinted = ref(false)
const printError = ref('')

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

// path-current: 单一路径，单一按钮
async function onPrint() {
  if (isPrinting.value || hasPrinted.value) return
  const imgUrl = pickedImgUrl.value || analysisSuccess
  isPrinting.value = true
  printError.value = ''
  console.log('[PrintTest/current] onPrint click', {
    source: pickedImgUrl.value ? 'picked' : 'builtin',
    imgName: pickedImgName.value || '(builtin analysis-success.png)',
    imgUrlType: imgUrl.slice(0, 40),
    len: imgUrl.length,
  })
  try {
    await printPhoto({
      goodImgUrl: imgUrl,
      jobName: pickedImgName.value
        ? `本地打印-${pickedImgName.value}`
        : `打印测试照片[current]`,
    })
    hasPrinted.value = true
    console.log('[PrintTest/current] printPhoto resolved (success)')
  } catch (e) {
    const err = e as Error
    console.error('[PrintTest/current] printPhoto rejected:', err)
    console.error('[PrintTest/current] stack:', err?.stack)
    printError.value = err?.message ?? '打印失败'
  } finally {
    isPrinting.value = false
  }
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

    <!-- path-current: 单一打印按钮 -->
    <div class="mode-block">
      <div class="mode-title">path-current 单路径打印</div>
      <div class="mode-grid">
        <button
          type="button"
          class="mode-btn mode-btn-single"
          :disabled="isPrinting || hasPrinted"
          @click="onPrint()"
        >
          <span class="mode-btn-label">打印</span>
        </button>
      </div>
    </div>

    <p v-if="printError" class="print-error">{{ printError }}</p>
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

</style>
