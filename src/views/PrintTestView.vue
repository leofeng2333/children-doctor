<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { HiTiPrinter } from '@/plugins/hiti-printer'
import { printPhoto } from '@/utils/print'
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

// 本地选择图片打印
const pickedImgUrl = ref<string>('') // 当前选中的图片（dataURL）
const pickedImgName = ref<string>('') // 选中的文件名（用于显示）
const fileInputRef = ref<HTMLInputElement | null>(null)

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
  })
  try {
    await printPhoto({
      goodImgUrl: imgUrl,
      qrcodeUrl: '',
      jobName: pickedImgName.value ? `本地打印-${pickedImgName.value}` : '打印测试照片',
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

// =================== 打印机检测 ===================
// 字段含义对照：
//   statusValue: 打印机原生 status 码（十六进制展示）
//   serviceErr : startService 返回的 ErrorCode（HiTi 自定义）
//   ribbon  : [ribbonType, remainCount]
//   counts  : [total, 4x6, 5x7, 6x8] 累计打印张数
interface PrinterProbe {
  serviceErr: { value: number; description: string } | null
  status: { statusValue: number; statusDescription: string } | null
  modelName: string
  serialNumber: string
  firmwareVersion: string
  ribbon: number[] | null
  counts: number[] | null
  /** 整轮检测耗时（ms） */
  elapsedMs: number
}

const isProbing = ref(false)
const probeData = ref<PrinterProbe | null>(null)
const probeError = ref('')

/** statusValue 转 hex 文本，方便对照 HiTi 手册 */
const statusHex = computed(() => {
  const v = probeData.value?.status?.statusValue
  return typeof v === 'number' ? '0x' + v.toString(16).padStart(8, '0').toUpperCase() : '—'
})

/** 高位 bit 用来判定"未连接/不可用"：0x00000080 表示未连接 */
const isDisconnected = computed(() => probeData.value?.status?.statusValue === 0x00000080)

/** 友好的状态文本 */
const statusText = computed(() => {
  if (probeError.value) return probeError.value
  if (!probeData.value) return ''
  if (isDisconnected.value) return '打印机未连接或未开机'
  const desc = probeData.value.status?.statusDescription
  return desc && desc !== 'null' ? desc : '打印机就绪'
})

/** 色带类型 → 文本（参考 vendor 手册：0=YMCKO, 1=K, 3=KO, ...） */
const ribbonTypeText = computed(() => {
  const r = probeData.value?.ribbon
  if (!r || r.length < 2) return '—'
  const map: Record<number, string> = {
    0: 'YMCKO（彩色+覆膜）',
    1: 'K（黑白）',
    3: 'KO（黑白+覆膜）',
  }
  const t = r[0] as number
  return map[t] ?? `类型 ${t}`
})

const ribbonRemain = computed(() => {
  const r = probeData.value?.ribbon
  return r && r.length >= 2 ? `${r[1]} 张` : '—'
})

/**
 * 一次性调用所有 SDK 只读接口，并把每条结果独立存放。
 * 任意一个失败不影响其它字段，最终用一个 ProbeResult 卡片统一展示。
 */
async function probePrinter() {
  if (isProbing.value) return
  isProbing.value = true
  probeError.value = ''
  probeData.value = null
  const t0 = Date.now()
  console.log('[PrintTest/probe] start')

  try {
    // 1) startService 必须先调，否则后续 USB_CHECK_PRINTER_STATUS 等
    //    直接返回 "Service is not start"。
    const svcRes = await HiTiPrinter.startService()
    console.log('[PrintTest/probe] startService:', svcRes)
    if (!svcRes.ok) {
      throw new Error(`startService 失败：${svcRes.error}`)
    }

    // 2) 并行拉所有只读字段，单独容错
    const [statusR, modelR, snR, fwR, ribbonR, countR] = await Promise.all([
      HiTiPrinter.getPrinterStatus(),
      HiTiPrinter.getModelName(),
      HiTiPrinter.getSerialNumber(),
      HiTiPrinter.getFirmwareVersion(),
      HiTiPrinter.getRibbonInfo(),
      HiTiPrinter.getPrintCount(),
    ])
    console.log('[PrintTest/probe] results:', { statusR, modelR, snR, fwR, ribbonR, countR })

    probeData.value = {
      serviceErr: svcRes.data ?? null,
      status: statusR.ok ? statusR.data ?? null : null,
      modelName: modelR.ok ? modelR.data ?? '' : '',
      serialNumber: snR.ok ? snR.data ?? '' : '',
      firmwareVersion: fwR.ok ? fwR.data ?? '' : '',
      ribbon: ribbonR.ok ? ribbonR.data ?? null : null,
      counts: countR.ok ? countR.data ?? null : null,
      elapsedMs: Date.now() - t0,
    }

    // statusR 失败其它都好：把它的错误顶到 probeError 里展示
    if (!statusR.ok) {
      probeError.value = `getPrinterStatus: ${statusR.error}`
    } else if (!modelR.ok || !snR.ok || !fwR.ok || !ribbonR.ok || !countR.ok) {
      // 至少一项失败，告知但不阻塞
      const partial: string[] = []
      if (!modelR.ok) partial.push(`model:${modelR.error}`)
      if (!snR.ok) partial.push(`serial:${snR.error}`)
      if (!fwR.ok) partial.push(`firmware:${fwR.error}`)
      if (!ribbonR.ok) partial.push(`ribbon:${ribbonR.error}`)
      if (!countR.ok) partial.push(`count:${countR.error}`)
      probeError.value = `部分字段获取失败：${partial.join('；')}`
    }
  } catch (e) {
    const err = e as Error
    console.error('[PrintTest/probe] failed:', err)
    probeError.value = err?.message ?? '检测失败'
  } finally {
    isProbing.value = false
    console.log('[PrintTest/probe] done, elapsed=' + (Date.now() - t0) + 'ms')
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

    <button class="print-btn" :disabled="isPrinting || hasPrinted" @click="onPrint">
      {{ hasPrinted ? '已打印完成' : isPrinting ? '正在准备打印…' : '打印测试照片' }}
    </button>

    <p v-if="printError" class="print-error">{{ printError }}</p>

    <!-- 打印机检测区域 -->
    <div class="probe-block">
      <div class="probe-row">
        <button
          class="probe-btn"
          type="button"
          :disabled="isProbing"
          @click="probePrinter"
        >
          🔍 {{ isProbing ? '检测中…' : '检测打印机' }}
        </button>
        <span v-if="isProbing" class="probe-spinner" aria-hidden="true"></span>
      </div>

      <p v-if="probeError" class="probe-error">{{ probeError }}</p>

      <div v-if="probeData" class="probe-card" :class="{ offline: isDisconnected }">
        <div class="probe-card-head">
          <span class="probe-card-title">打印机信息</span>
          <span class="probe-card-state" :class="{ offline: isDisconnected }">
            {{ statusText }}
          </span>
        </div>

        <ul class="probe-list">
          <li>
            <span class="k">型号</span>
            <span class="v">{{ probeData.modelName || '—' }}</span>
          </li>
          <li>
            <span class="k">序列号</span>
            <span class="v">{{ probeData.serialNumber || '—' }}</span>
          </li>
          <li>
            <span class="k">固件版本</span>
            <span class="v">{{ probeData.firmwareVersion || '—' }}</span>
          </li>
          <li>
            <span class="k">status 码</span>
            <span class="v mono">{{ statusHex }}</span>
          </li>
          <li>
            <span class="k">色带</span>
            <span class="v">{{ ribbonTypeText }} · 余量 {{ ribbonRemain }}</span>
          </li>
          <li>
            <span class="k">打印张数</span>
            <span class="v">
              <template v-if="probeData.counts && probeData.counts.length >= 4">
                总 {{ probeData.counts[0] }} · 4×6 {{ probeData.counts[1] }} ·
                5×7 {{ probeData.counts[2] }} · 6×8 {{ probeData.counts[3] }}
              </template>
              <template v-else>—</template>
            </span>
          </li>
          <li>
            <span class="k">Service Code</span>
            <span class="v mono">
              <template v-if="probeData.serviceErr">
                0x{{ probeData.serviceErr.value.toString(16).toUpperCase().padStart(4, '0') }}
                · {{ probeData.serviceErr.description || 'OK' }}
              </template>
              <template v-else>—</template>
            </span>
          </li>
          <li>
            <span class="k">耗时</span>
            <span class="v">{{ probeData.elapsedMs }} ms</span>
          </li>
        </ul>
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

/* ===== 打印机检测区域 ===== */
.probe-block {
  width: 425px;
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.probe-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.probe-btn {
  flex: 1;
  height: 72px;
  background: #ffffff;
  color: #1f6feb;
  border: 1px solid #1f6feb;
  border-radius: 12px;
  font-size: 22px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.2s ease,
    transform 0.2s ease;

  &:active:not(:disabled) {
    background: #eaf2ff;
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.probe-spinner {
  width: 22px;
  height: 22px;
  border: 3px solid #d0d7de;
  border-top-color: #1f6feb;
  border-radius: 50%;
  animation: probe-spin 0.8s linear infinite;
}

@keyframes probe-spin {
  to {
    transform: rotate(360deg);
  }
}

.probe-error {
  margin: 0;
  font-size: 18px;
  color: #ff4d4f;
  text-align: center;
}

.probe-card {
  background: #ffffff;
  border-radius: 16px;
  padding: 20px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  border: 1px solid #e5e7eb;

  &.offline {
    border-color: #ffccc7;
    background: #fff8f7;
  }
}

.probe-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  margin-bottom: 12px;
  border-bottom: 1px dashed #e5e5ea;
}

.probe-card-title {
  font-size: 22px;
  font-weight: 700;
  color: #111;
}

.probe-card-state {
  font-size: 18px;
  font-weight: 600;
  color: #2a8a3e;
  padding: 4px 12px;
  border-radius: 999px;
  background: #e8f7ec;

  &.offline {
    color: #c53030;
    background: #ffe7e5;
  }
}

.probe-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;

  li {
    display: flex;
    align-items: center;
    font-size: 18px;
    min-height: 32px;
  }

  .k {
    flex: 0 0 130px;
    color: #888;
  }

  .v {
    flex: 1;
    color: #1a1a1a;
    word-break: break-all;
    text-align: right;

    &.mono {
      font-family: 'SF Mono', Menlo, Consolas, monospace;
      font-size: 16px;
    }
  }
}
</style>
