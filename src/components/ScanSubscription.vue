<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { Toast } from '@capacitor/toast'
import { useAnalysisStore } from '@/stores'
import { bindPhoneToLlmAnalysis, createFollowTask } from '@/utils/service'
import { DualCamera } from '@/plugins/dual-camera'
import { printPhoto } from '@/utils/print-current'

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
 * 非空时 NamePhoneDialog 切换到二维码视图；关闭弹窗时清空。
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
  // 关闭弹窗时清空绑定状态，避免下次打开直接落在 qrcode / loading 视图
  qrcodeUrl.value = ''
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
    // 2) 拉取公众号关注二维码（git 历史曾用 createSubscriptionTask，
    //    路径 /api/wechat/follow-task/create，返回 { qrcodeUrl, followTaskId }）
    const { qrcodeUrl: url } = await createFollowTask()
    if (!url) {
      throw new Error('未返回二维码图片')
    }
    qrcodeUrl.value = url
  } catch (e: any) {
    console.error('[ScanSubscription] bindPhoneToLlmAnalysis/createFollowTask failed:', e)
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
    <!-- 未关注态: 左 按钮（点击弹 NamePhoneDialog） / 右 两按钮上下排 -->
    <div class="scan-row">
      <div class="qrcode-block">
        <div class="btn-tips">争做低碳小卫士！</div>
        <PrimaryButton type="button" class="qrcode-btn" :aria-label="hasLlmAnalysisId ? '查看电子版报告' : '报告正在准备中'"
          @click="handleOpenNamePhoneDialog">
          点击获取
          电子照片
        </PrimaryButton>
      </div>

      <div class="action-buttons">
        <PrimaryButton class="action-btn primary" :class="{ 'is-printed': hasPrinted }" type="button"
          :disabled="isPrinting || hasPrinted || !hasLlmAnalysisId" @click="onPrint(goodImgUrl)">
          <template v-if="hasPrinted">
            <span>已打印完成</span>
            <span>请在下方取走宝贝照片</span>
          </template>
          <template v-else>{{ printButtonLabel }}</template>
        </PrimaryButton>
        <PrimaryButton class="action-btn secondary" type="button" :disabled="isFinishing || !hasLlmAnalysisId"
          @click="onFinish">
          直接结束本次诊断
        </PrimaryButton>
      </div>
    </div>

    <p v-if="printError" class="print-error">{{ printError }}</p>

    <!-- 姓名 + 手机号输入弹窗 -->
    <NamePhoneDialog :visible="showNamePhoneDialog" :llm-analysis-id="llmAnalysisId" :qrcode-url="qrcodeUrl"
      :loading="isBinding" @close="handleNamePhoneDialogClose" @submit="handleNamePhoneDialogSubmit" />
  </div>
</template>

<style scoped lang="scss">
.scan-row {
  display: flex;
  align-items: center;
  gap: 30px;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    'PingFang SC',
    'Microsoft YaHei',
    sans-serif;
}

.qrcode-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.btn-tips {
  font-weight: 700px;
  font-size: 24px;
  line-height: 30px;
  color: #F2684E;
}

/* 原二维码位置（200×200）改为按钮：黄色背景 + 搜索图标 + "查看报告" 文字 */
.qrcode-btn {
  width: 218px;
  height: 200px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 0 30px;
  border: none;
  border-radius: 50px;
  cursor: pointer;
  color: #000;
  line-height: 45px;
  font-weight: 700;
  font-family: inherit;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;

  &:active {
    transform: scale(0.98);
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.qrcode-btn-icon {
  display: block;
  color: inherit;
}

.qrcode-btn-text {
  font-size: 22px;
  font-weight: 700;
  color: inherit;
  line-height: 1;
}

.qrcode-desc {
  font-size: 20px;
  font-weight: 400;
  line-height: 1.5;
  color: #000;
  white-space: nowrap;
  text-align: center;
}

.action-buttons {
  display: flex;
  flex-direction: column;
  gap: 20px;
  flex-shrink: 0;
}

.action-btn {
  width: 425px;
  height: 95px;
  font-family: inherit;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;

  &:active {
    transform: scale(0.98);
  }
}

.btn-icon {
  width: 20px;
  height: 20px;
  display: block;
}

.is-printed {
  flex-direction: column;
  gap: 4px;
  padding: 18px 32px;

  &>span {
    font-size: 22px;
    font-weight: 700;
    line-height: 1.2;
  }
}

.print-error {
  margin-top: 12px;
  font-size: 14px;
  color: #ff4d4f;
  text-align: center;
}
</style>
