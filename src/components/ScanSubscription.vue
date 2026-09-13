<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Toast } from '@capacitor/toast'
import { useAnalysisStore } from '@/stores'
import { bindPhoneToLlmAnalysis } from '@/utils/service'
import { DualCamera } from '@/plugins/dual-camera'
import { printPhoto } from '@/utils/print-current'
// 公众号关注二维码改为本地静态资源（无需再请求 /api/wechat/follow-task/create）。
// Vite 会把 import 解析为打包后的资源 URL，可以直接作为 <img :src> 使用。
import staticQrcodeUrl from '@/assets/images/qrcode.jpg'

interface Props {
  /** 主图 URL（不健康面型路径下：右半图矫正后面容；健康面型路径下：analysis-success.png） */
  goodImgUrl?: string
}

withDefaults(defineProps<Props>(), {
  goodImgUrl: '',
})

const router = useRouter()

/**
 * 把 llmAnalysisId（后端可能返 string 也可能返 number）规范化为字符串。
 * 0 / NaN / 空字符串都视为未就绪，返回空字符串让上层走占位分支。
 */
function normalizeLlmAnalysisId(raw: string | number | null | undefined): string {
  if (raw === null || raw === undefined) return ''
  if (typeof raw === 'number') {
    return Number.isFinite(raw) && raw > 0 ? String(raw) : ''
  }
  const trimmed = String(raw).trim()
  return trimmed.length > 0 ? trimmed : ''
}

const analysisStore = useAnalysisStore()
const { result: analysisResult } = storeToRefs(analysisStore)
const llmAnalysisId = computed(() =>
  normalizeLlmAnalysisId(analysisResult.value?.llmAnalysis.llmAnalysisId),
)
const hasLlmAnalysisId = computed(() => !!llmAnalysisId.value)
const isFinishing = ref(false)
const isPrinting = ref(false)
const hasPrinted = ref(false)
const printError = ref('')
/** 是否显示"姓名 + 手机号"弹窗（NamePhoneDialog） */
const showNamePhoneDialog = ref(false)
/**
 * 绑定成功后从后端拉到的公众号二维码图片 URL。
 * 非空时 NamePhoneDialog 切换到二维码视图；保留以便关闭弹窗后再次打开仍展示二维码。
 */
const qrcodeUrl = ref('')
/** 正在调用"绑定 + 取二维码"接口 —— 弹窗切到 loading、关闭按钮禁用 */
const isBinding = ref(false)

const printButtonLabel = computed(() => {
  if (isPrinting.value) return '正在准备打印...'
  if (hasPrinted.value) return '已打印完成\n请在下方取走宝贝照片'
  return '打印带走宝贝照片'
})

function handleOpenNamePhoneDialog() {
  // 始终允许点击：若 llmAnalysisId 为空，由 NamePhoneDialog 内部 Toast 提示。
  showNamePhoneDialog.value = true
}

function handleNamePhoneDialogClose() {
  showNamePhoneDialog.value = false
  // 保留 qrcodeUrl：已绑定后再次打开弹窗，直接展示二维码，不再回到 input UI
  isBinding.value = false
}

async function handleNamePhoneDialogSubmit(payload: {
  name: string
  phone: string
  llmAnalysisId: string
}) {
  if (isBinding.value) return
  isBinding.value = true
  try {
    // 1) 绑定手机号 到 llmAnalysisId（h5 同名接口 /api/ai/llm-task/bind-phone，
    //    此接口按 h5 约定只接收 llmAnalysisId + phone，name 不参与绑定）
    await bindPhoneToLlmAnalysis({
      llmAnalysisId: payload.llmAnalysisId,
      phone: payload.phone,
    })
    // 2) 直接使用本地静态二维码资源（Vite 解析后的 URL 字符串），
    //    不再请求 /api/wechat/follow-task/create 接口。
    qrcodeUrl.value = staticQrcodeUrl
  } catch (e: any) {
    console.error('[ScanSubscription] bindPhoneToLlmAnalysis failed:', e)
    await Toast.show({
      text: e?.message ?? '绑定失败，请稍后重试',
      position: 'center',
      duration: 'short',
    })
  } finally {
    isBinding.value = false
  }
}

async function onFinish() {
  if (isFinishing.value) return
  isFinishing.value = true
  try {
    // Always navigate first so the user isn't blocked on I/O.
    await router.push('/')
  } finally {
    try {
      const result = await DualCamera.clearImageCache()
      console.log('[ScanSubscription] image cache cleared, removed:', result.removed)
    } catch (err) {
      console.warn('[ScanSubscription] clearImageCache failed (non-fatal):', err)
    }
    isFinishing.value = false
  }
}

async function onPrint(goodImgUrl: string) {
  if (isPrinting.value || hasPrinted.value) return
  if (!goodImgUrl) {
    printError.value = '主图尚未就绪，请稍后再试'
    return
  }
  printError.value = ''
  isPrinting.value = true
  try {
    console.log('[ScanSubscription] onPrint: goodImg=', goodImgUrl)
    // 走 HiTi 专用 USB 照片打印机（HiTiPrinterCurrent 单 plugin 方案）；
    // HiTi 直接打图，不支持 QR 排版 —— HiTi 不可用时不再降级到 system printer
    // （v2 改造前用过旧 @/utils/print，会自动 fallback 到 @capgo/capacitor-printer
    //  系统对话框把 goodImg + QR 拼成 HTML 打印 —— 该路径已弃用，QR 不再打印）。
    // 原二维码位置改为按钮，点击弹出 NamePhoneDialog 收集姓名 + 手机号。
    await printPhoto({
      goodImgUrl,
      jobName: '宝贝照片',
    })
    // 仅打印走通后锁定按钮：原生平台 printHtml 弹系统对话框，用户取消不会
    // reject（Capacitor Printer 不感知），因此 await 正常返回即视为成功。
    hasPrinted.value = true
    printError.value = ''
  } catch (e) {
    printError.value = (e as Error)?.message ?? '打印失败'
    console.error('[ScanSubscription] print failed:', e)
  } finally {
    isPrinting.value = false
  }
}
</script>

<template>
  <div class="scan-subscription">
    <!-- 上排：打印 + 结束体验，两个按钮并排均分 -->
    <div class="scan-actions">
      <PrimaryButton class="scan-btn scan-btn--print" :class="{ 'is-printed': hasPrinted }" type="button"
        :disabled="isPrinting || hasPrinted || !hasLlmAnalysisId" @click="onPrint(goodImgUrl)">
        <template v-if="hasPrinted">
          <span class="scan-btn__line">已打印完成</span>
          <span class="scan-btn__line">请在下方取走宝贝照片</span>
        </template>
        <template v-else>{{ printButtonLabel }}</template>
      </PrimaryButton>
      <PrimaryButton class="scan-btn scan-btn--finish" type="button" :disabled="isFinishing || !hasLlmAnalysisId"
        @click="onFinish">
        直接结束本次体验
      </PrimaryButton>
    </div>

    <!-- 下排：订阅公众号（拿电子版照片），通栏按钮，触发 NamePhoneDialog -->
    <PrimaryButton class="scan-btn scan-btn--subscribe" type="button"
      :aria-label="hasLlmAnalysisId ? '查看电子版报告' : '报告正在准备中'" @click="handleOpenNamePhoneDialog">
      争做低碳小卫士！获取永久电子版照片及预测结果
      <!-- <span class="scan-btn__title">争做低碳小卫士！</span>
      <span class="scan-btn__desc">获取永久电子版照片及预测结果</span> -->
    </PrimaryButton>

    <p v-if="printError" class="print-error">{{ printError }}</p>

    <!-- 姓名 + 手机号输入弹窗 -->
    <NamePhoneDialog :visible="showNamePhoneDialog" :llm-analysis-id="llmAnalysisId" :qrcode-url="qrcodeUrl"
      :loading="isBinding" @close="handleNamePhoneDialogClose" @submit="handleNamePhoneDialogSubmit" />
  </div>
</template>

<style scoped lang="scss">
/* ===== 整体外层 ===== */
.scan-subscription {
  width: 100%;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
}

/* ===== 上排：两个按钮并排均分 ===== */
.scan-actions {
  display: flex;
  gap: 20px;
  margin-bottom: 20px;
}

/* ===== 所有 scan 按钮的基类：覆盖 PrimaryButton 默认的 90% 宽 + 居中 ===== */
.scan-btn {
  width: auto;
  margin: 0;
  height: 76px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 700;
  color: #fff;
  box-shadow: none;
}

/* 上排里的两个按钮：均分父容器宽度 */
.scan-actions .scan-btn {
  flex: 1 1 0;
}

/* ===== 下排：通栏订阅按钮（多行布局） ===== */
.scan-btn--subscribe {
  width: 100%;
  /* 单独成行，不需要参与 flex grow */
  flex: 0 0 auto;
  height: auto;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 6px;
  padding: 22px 40px;
  text-align: left;
}

.scan-btn__title {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.2;
  color: inherit;
}

.scan-btn__desc {
  font-size: 20px;
  font-weight: 400;
  line-height: 1.2;
  color: inherit;
}

/* ===== 打印成功态：两行小字（沿用旧 is-printed 视觉） ===== */
.scan-btn--print.is-printed {
  flex-direction: column;
  gap: 4px;
  padding: 18px 24px;
}

.scan-btn--print.is-printed .scan-btn__line {
  font-size: 22px;
  font-weight: 700;
  line-height: 1.2;
}

/* ===== 错误提示 ===== */
.print-error {
  margin-top: 12px;
  font-size: 14px;
  color: #ff4d4f;
  text-align: center;
}
</style>
