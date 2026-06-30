<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSubscriptionScan } from '@/composables/useSubscriptionScan'
import { DualCamera } from '@/plugins/dual-camera'
import { printPhotoWithQrcode } from '@/utils/print'

interface Props {
  /** 主图 URL（不健康面型路径下：右半图矫正后面容；健康面型路径下：analysis-success.png） */
  goodImgUrl?: string
}

withDefaults(defineProps<Props>(), {
  goodImgUrl: '',
})

const router = useRouter()

const hadSubscription = ref(false)
const qrcodeUrl = ref('')
const isFinishing = ref(false)
const isPrinting = ref(false)
const printError = ref('')

let cleanup: (() => void) | undefined

// onMounted(async () => {
//   console.log('[ScanSubscription] onMounted')
//   cleanup = useSubscriptionScan((state) => {
//     console.log('[ScanSubscription] state updated:', state)
//     if (state.isSubscribed) {
//       hadSubscription.value = true
//     }
//     if (state.qrcodeUrl) {
//       qrcodeUrl.value = state.qrcodeUrl
//     }
//   })

//   console.log('[ScanSubscription] calling init...')
//   await useSubscriptionScan()
//   console.log('[ScanSubscription] init done')
// })

// onUnmounted(() => {
//   cleanup?.()
// })

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
  if (isPrinting.value) return
  if (!goodImgUrl) {
    printError.value = '主图尚未就绪，请稍后再试'
    return
  }
  printError.value = ''
  isPrinting.value = true
  try {
    // 二维码未就绪时也允许打印：模板里只占位即可
    const finalQrcodeUrl = qrcodeUrl.value || ''
    console.log(
      '[ScanSubscription] onPrint: goodImg=',
      goodImgUrl,
      'qrcode=',
      finalQrcodeUrl || '(empty)',
    )
    await printPhotoWithQrcode({
      goodImgUrl,
      qrcodeUrl: finalQrcodeUrl,
      jobName: '宝贝照片',
    })
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
          <img :src="qrcodeUrl" alt="qrcode" />
        </div>
        <div class="qrcode-desc">扫一扫获取电子版</div>
      </div>

      <div class="action-buttons">
        <PrimaryButton class="action-btn primary" type="button" :disabled="isPrinting" @click="onPrint(goodImgUrl)">{{
          isPrinting ? '正在准备打印...' : '打印带走宝贝照片' }}</PrimaryButton>
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

  img {
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

.action-btn.primary {}

.action-btn.secondary {}

.btn-icon {
  width: 20px;
  height: 20px;
  display: block;
}

.print-error {
  margin-top: 12px;
  font-size: 14px;
  color: #ff4d4f;
  text-align: center;
}
</style>
