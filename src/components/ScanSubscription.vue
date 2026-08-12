<script setup lang="ts">
import { computed, ref } from 'vue'
import QRCode from 'qrcode'
import { useRouter } from 'vue-router'
import VueQrcode from 'vue-qrcode'
import { useQrcodeIdid } from '@/composables/useQrcode'
import { DualCamera } from '@/plugins/dual-camera'
import { printPhoto } from '@/utils/print'

interface Props {
  /** 主图 URL（不健康面型路径下：右半图矫正后面容；健康面型路径下：analysis-success.png） */
  goodImgUrl?: string
}

withDefaults(defineProps<Props>(), {
  goodImgUrl: '',
})

const router = useRouter()

// 直接使用本地二维码 composable：链接从 .env 里的 VITE_QRCODE_BASE_URL
// 拼出，idid 在 localStorage 持久化。
const { url: qrcodeUrl } = useQrcodeIdid()
const isFinishing = ref(false)
const isPrinting = ref(false)
const hasPrinted = ref(false)
const printError = ref('')

const printButtonLabel = computed(() => {
  if (isPrinting.value) return '正在准备打印...'
  if (hasPrinted.value) return '已打印完成\n请在下方取走宝贝照片'
  return '打印带走宝贝照片'
})

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
    // 把二维码 URL 渲染成 PNG dataURL 传给打印模板。
    // System PrintManager 模板只接受 dataURL/file/http(s)，直接传 http(s) 链接
    // 在 Android WebView 空 origin 下会加载失败。
    let qrcodeDataUrl = ''
    if (qrcodeUrl.value) {
      try {
        qrcodeDataUrl = await QRCode.toDataURL(qrcodeUrl.value, {
          width: 360,
          margin: 1,
          errorCorrectionLevel: 'M',
        })
      } catch (e) {
        console.warn('[ScanSubscription] QRCode.toDataURL failed, print without qrcode:', e)
      }
    }
    console.log(
      '[ScanSubscription] onPrint: goodImg=',
      goodImgUrl,
      'qrcodeDataUrlLen=',
      qrcodeDataUrl.length,
    )
    // 默认走 HiTi 专用 USB 照片打印机；HiTi 不可用时由 printPhoto 内部
    // 自动降级到 @capgo/capacitor-printer 系统打印对话框（含二维码排版）。
    await printPhoto({
      goodImgUrl,
      qrcodeUrl: qrcodeDataUrl,
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
    <!-- 未关注态: 左 QR + 描述 / 右 两按钮上下排 -->
    <div class="scan-row">
      <div class="qrcode-block">
        <div class="qrcode-container">
          <VueQrcode
            v-if="qrcodeUrl"
            :value="qrcodeUrl"
            :width="200"
            :height="200"
            :margin="2"
            :color="{ dark: '#000000ff', light: '#ffffffff' }"
            type="image/png"
          />
        </div>
        <div class="qrcode-desc">扫一扫获取电子版</div>
      </div>

      <div class="action-buttons">
        <PrimaryButton
          class="action-btn primary"
          :class="{ 'is-printed': hasPrinted }"
          type="button"
          :disabled="isPrinting || hasPrinted"
          @click="onPrint(goodImgUrl)"
        >
          <template v-if="hasPrinted">
            <span>已打印完成</span>
            <span>请在下方取走宝贝照片</span>
          </template>
          <template v-else>{{ printButtonLabel }}</template>
        </PrimaryButton>
        <PrimaryButton class="action-btn secondary" type="button" :disabled="isFinishing" @click="onFinish">完成诊断
        </PrimaryButton>
      </div>
    </div>

    <p v-if="printError" class="print-error">{{ printError }}</p>

    <!-- 已关注态 -->
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

.qrcode-container {
  width: 200px;
  height: 200px;
  display: flex;
  align-items: center;
  justify-content: center;

  img,
  canvas {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }
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

  & > span {
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
