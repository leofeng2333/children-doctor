<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { printPhoto, printPageMounted, printPageUnmounted } from '@/utils/print'
// Vite 的 `?url` 拿 URL；`?inline` 直接拿 base64 dataURL（编译期内联）。
// 用 inline 让原生侧跳过 readImageAsBase64, 避免 Vite 的 /assets/*.png
// 在 Android Capacitor 里没有对应 ContentResolver 条目而读不到的问题。
import analysisSuccess from '@/assets/images/analysis-success.png?inline'

const router = useRouter()
const goBack = () => router.push('/')

// 与 ScanSubscription.vue 完全一致：单次打印入口, 失败自动降级
const isPrinting = ref(false)
const hasPrinted = ref(false)
const printError = ref('')

// 页面级 HiTi USB 占用：进入页面 init（bind service + claim USB），离开页面 release。
// 不在 app 启动时 init HiTi，是为了避免与 UVC camera 抢占同一 USB bus。
onMounted(async () => {
  await printPageMounted()
})
onBeforeUnmount(async () => {
  // router.push 之后 beforeUnmount 触发；这时先 release USB，让其它页面（特别是
  // CameraCaptureView 之类需要 USB 的）能继续使用 USB bus。
  await printPageUnmounted()
})
onUnmounted(async () => {
  // 兜底：万一 onBeforeUnmount 漏了，再 release 一次（HiTi release 是 idempotent，
  // serviceConnector 已经是 null 时第二次 release 是 no-op）。
  await printPageUnmounted()
})

// 本地选择图片打印
const pickedImgUrl = ref<string>('') // 当前选中的图片（dataURL）
const pickedImgName = ref<string>('') // 选中的文件名（用于显示）
const fileInputRef = ref<HTMLInputElement | null>(null)

/**
 * 打印模式（仅 PrintTestView 多模式对比诊断使用）：
 *
 * - 'current'：与 v1.0.15-print-stable 一致（TS letterbox + Java 不旋转不 normalize，
 *   raw bitmap → SDK）。生产路径，验证根因是不是"TS letterbox 输出 portrait bitmap"。
 *
 * - 'cover-fit'：TS 强制 cover-fit 到 1536×1024 landscape + Java 不动。
 *   若直接打印恢复正常 ⇒ TS letterbox 是元凶。
 *
 * - 'portrait-rotate'：TS letterbox + Java 检测 portrait bitmap 后 90° 旋转。
 *   验证 Java 端补 portrait 旋转能否修复直接打印卡 25s。
 *
 * - 'portrait-rotate-normalize'：TS letterbox + Java portrait 旋转 + normalize crop。
 *   510e621 时的混合方案。
 */
type BitmapProcessMode = 'current' | 'cover-fit' | 'portrait-rotate' | 'portrait-rotate-normalize'
const MODE_OPTIONS: ReadonlyArray<{ value: BitmapProcessMode; label: string; hint: string }> = [
  { value: 'current', label: '1. current', hint: 'TS letterbox + Java 不动（生产路径）' },
  { value: 'cover-fit', label: '2. cover-fit', hint: 'TS 强制 1536×1024 + Java 不动' },
  { value: 'portrait-rotate', label: '3. portrait-rotate', hint: 'TS letterbox + Java 90° 旋转' },
  { value: 'portrait-rotate-normalize', label: '4. portrait-rotate-normalize', hint: 'TS letterbox + Java 旋转 + normalize' },
]
const selectedMode = ref<BitmapProcessMode>('current')

function onPickClick() {
  // 重置 value，确保同一张图也能再次触发 change
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
    console.log('[PrintTest] picked image', {
      name: file.name,
      type: file.type,
      size: file.size,
      dataUrlPrefix: result.slice(0, 40),
      dataUrlLen: result.length,
    })
  }
  reader.onerror = () => {
    printError.value = '读取本地图片失败'
    console.error('[PrintTest] FileReader error:', reader.error)
  }
  reader.readAsDataURL(file)
}

function onClearPicked() {
  pickedImgUrl.value = ''
  pickedImgName.value = ''
  hasPrinted.value = false
  if (fileInputRef.value) fileInputRef.value.value = ''
}

// 实际打印：优先用选中的本地图，没有就用内置的 analysis-success.png
async function onPrint() {
  if (isPrinting.value || hasPrinted.value) return
  // 没选本地图时，用内置图，并标记"已用过内置图"以便禁用
  const imgUrl = pickedImgUrl.value || analysisSuccess
  const isBuiltin = !pickedImgUrl.value
  isPrinting.value = true
  printError.value = ''
  console.log('[PrintTest] onPrint click', {
    source: isBuiltin ? 'builtin' : 'picked',
    imgName: pickedImgName.value || '(builtin analysis-success.png)',
    imgUrlType: imgUrl.slice(0, 40),
    len: imgUrl.length,
    mode: selectedMode.value,
  })
  try {
    await printPhoto({
      goodImgUrl: imgUrl,
      qrcodeUrl: '',
      jobName: pickedImgName.value ? `本地打印-${pickedImgName.value}` : '打印测试照片',
      printMode: selectedMode.value,
    })
    hasPrinted.value = true
    console.log('[PrintTest] printPhoto resolved (success)')
  } catch (e) {
    const err = e as Error
    console.error('[PrintTest] printPhoto rejected:', err)
    console.error('[PrintTest] stack:', err?.stack)
    printError.value = err?.message ?? '打印失败'
  } finally {
    isPrinting.value = false
  }
}

// 用户可手动从这里取出 native 日志路径（HiTi 打印成功后）
// 在 vConsole 里查 printPhoto 内部的 "[print/HiTi] native log session started:"
// 可找到具体路径（/storage/emulated/0/Android/data/com.children.doctor/files/print_logs/print_*.log）。
const logHint = `日志路径（HiTi 打印后）：\n/storage/emulated/0/Android/data/com.children.doctor/files/print_logs/`
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

    <!-- 打印模式选择（仅 PrintTestView 诊断使用，生产路径不暴露） -->
    <fieldset class="mode-block" :disabled="isPrinting || hasPrinted">
      <legend>打印模式（多模式对比诊断）</legend>
      <label
        v-for="opt in MODE_OPTIONS"
        :key="opt.value"
        class="mode-row"
        :class="{ active: selectedMode === opt.value }"
      >
        <input
          type="radio"
          name="print-mode"
          :value="opt.value"
          v-model="selectedMode"
        />
        <div class="mode-text">
          <div class="mode-label">{{ opt.label }}</div>
          <div class="mode-hint">{{ opt.hint }}</div>
        </div>
      </label>
    </fieldset>

    <button class="print-btn" :disabled="isPrinting || hasPrinted" @click="onPrint">
      {{ hasPrinted ? '已打印完成' : isPrinting ? '正在准备打印…' : '打印测试照片' }}
    </button>

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

.print-btn {
  margin-top: 32px;
  width: 425px;
  height: 95px;
  background: #ff9900;
  color: #fff;
  border: none;
  border-radius: 16px;
  font-size: 28px;
  font-weight: 700;
  cursor: pointer;
  transition:
    transform 0.2s ease,
    opacity 0.2s ease;

  &:active:not(:disabled) {
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
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
  border: 1px solid #ddd;
  border-radius: 12px;
  padding: 16px 20px;
  background: #fff;
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;

  legend {
    font-size: 20px;
    font-weight: 600;
    color: #111;
    padding: 0 8px;
  }

  &:disabled {
    opacity: 0.5;
  }
}

.mode-row {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 8px 4px;
  border-radius: 8px;
  cursor: pointer;

  &:hover:not([disabled]) {
    background: #f5f5f7;
  }

  &.active {
    background: #fff5e6;
  }

  input[type='radio'] {
    margin-top: 6px;
    width: 20px;
    height: 20px;
    flex-shrink: 0;
    accent-color: #ff9900;
  }
}

.mode-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.mode-label {
  font-size: 20px;
  font-weight: 600;
  color: #222;
}

.mode-hint {
  font-size: 16px;
  color: #666;
}

</style>
