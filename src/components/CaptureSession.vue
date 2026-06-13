<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { DualCamera } from '@/plugins/dual-camera/src/index'
import type { DualCameraPhoto } from '@/plugins/dual-camera/src/definitions'
import { Capacitor } from '@capacitor/core'

const props = defineProps<{
  round: number
}>()

const emit = defineEmits<{
  /** 用户点击确认后（可能经过多次重拍），通知父组件本轮结束 */
  confirmed: [photo: DualCameraPhoto]
}>()

const COUNTDOWN_SECONDS = 5

const isCounting = ref(false)   // 倒计时阶段
const isCapturing = ref(false)  // 真正调用 capture() 阶段
const countdown = ref(COUNTDOWN_SECONDS)  // 剩余秒数
const pendingPhoto = ref<DualCameraPhoto | null>(null)
const errorMsg = ref('')

let countdownTimer: ReturnType<typeof setInterval> | null = null
let captureTimer: ReturnType<typeof setTimeout> | null = null

const countdownText = computed(() => `倒计时：${countdown.value}/${COUNTDOWN_SECONDS}`)

const clearTimers = () => {
  if (countdownTimer) {
    clearInterval(countdownTimer)
    countdownTimer = null
  }
  if (captureTimer) {
    clearTimeout(captureTimer)
    captureTimer = null
  }
}

onBeforeUnmount(clearTimers)

const startCountdown = () => {
  isCounting.value = true
  countdown.value = COUNTDOWN_SECONDS
  errorMsg.value = ''

  countdownTimer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      if (countdownTimer) {
        clearInterval(countdownTimer)
        countdownTimer = null
      }
      // 真正拍照
      void doCapture()
    }
  }, 1000)
}

const cancelCountdown = () => {
  clearTimers()
  isCounting.value = false
  countdown.value = COUNTDOWN_SECONDS
}

const doCapture = async () => {
  console.log('[CaptureSession] doCapture 开始')
  isCounting.value = false
  isCapturing.value = true
  try {
    const result = (await DualCamera.capture()) as Record<string, unknown>
    console.log('[CaptureSession] capture result:', JSON.stringify(result))
    const frontUrl = result.cameraUrl0 as string
    const backUrl = result.cameraUrl1 as string

    if (!frontUrl || !backUrl) {
      throw new Error(`拍摄返回的 URI 为空: cameraUrl0=${frontUrl}, cameraUrl1=${backUrl}`)
    }

    const photo: DualCameraPhoto = {
      frontCameraUrl: frontUrl,
      backCameraUrl: backUrl,
      frontCameraPath: result.cameraPath0 as string,
      backCameraPath: result.cameraPath1 as string,
      timestamp: result.timestamp as number,
    }

    console.log('[CaptureSession] 复制图片到外部缓存:', { frontUrl, backUrl })
    const [frontResult, backResult] = await Promise.all([
      DualCamera.copyImageToExternalCache({ uri: frontUrl }),
      DualCamera.copyImageToExternalCache({ uri: backUrl }),
    ])
    console.log('[CaptureSession] 复制结果:', JSON.stringify({ frontResult, backResult }))
    photo.frontDisplayUrl = Capacitor.convertFileSrc(frontResult.uri)
    photo.backDisplayUrl = Capacitor.convertFileSrc(backResult.uri)

    console.log('[CaptureSession] 拍照成功, round:', props.round)
    console.log('photo', photo)

    pendingPhoto.value = photo
    console.log('[CaptureSession] pendingPhoto.value 已赋值, is pendingPhoto truthy:', !!pendingPhoto.value)
  } catch (e) {
    errorMsg.value = (e as Error).message
    console.error('[CaptureSession] capture failed:', e)
  } finally {
    isCapturing.value = false
    countdown.value = COUNTDOWN_SECONDS
    console.log('[CaptureSession] doCapture 结束, pendingPhoto:', pendingPhoto.value ? '已设置' : '未设置', 'errorMsg:', errorMsg.value || '无')
  }
}

const handleCaptureClick = () => {
  if (isCapturing.value) return
  if (isCounting.value) {
    cancelCountdown()
    return
  }
  startCountdown()
}

const handleRetry = async () => {
  console.log('[CaptureSession] retry')
  pendingPhoto.value = null
  countdown.value = COUNTDOWN_SECONDS
  try {
    await DualCamera.resumePreviewFromPhotos()
  } catch (_) {}
}

const handleConfirm = async () => {
  if (!pendingPhoto.value) return
  console.log('[CaptureSession] confirm')
  try {
    await DualCamera.resumePreviewFromPhotos()
  } catch (_) {}
  emit('confirmed', pendingPhoto.value)
  pendingPhoto.value = null
}
</script>

<template>
  <div class="capture-session">
    <!-- 顶部提示 -->
    <div v-if="pendingPhoto" class="confirm-tip">请确认图片</div>
    <div v-else class="session-tip">
      <span class="countdown-text">{{ countdownText }}</span>
      <span class="tip-text"> 请正面看向屏幕 </span>
      <span v-if="round === 1 && !pendingPhoto" class="round-badge">开心地露出牙齿拍摄哦！</span>
      <span v-if="round === 2 && !pendingPhoto" class="round-badge"
        >合上小嘴巴，让我来看看面部！</span
      >
    </div>

    <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>

    <!-- 操作区 -->
    <div class="session-actions">
      <template v-if="!pendingPhoto">
        <PrimaryButton
          class="capture-btn"
          :class="{ counting: isCounting, capturing: isCapturing }"
          :disabled="isCapturing"
          @click="handleCaptureClick"
        >
          {{ isCapturing ? '拍摄中...' : isCounting ? '取消' : '咔嚓！' }}
        </PrimaryButton>
      </template>
      <div v-else class="review-actions">
        <PrimaryButton class="action-btn confirm-btn" @click="handleConfirm">下一步</PrimaryButton>
        <PrimaryButton class="action-btn retry-btn" @click="handleRetry">重新拍摄</PrimaryButton>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.capture-session {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  justify-content: space-between;
}

.confirm-tip {
  font-size: 64px;
  font-weight: 700;
  line-height: 200px;
  min-height: 200px;
}

.session-tip {
  display: flex;
  flex-direction: column;
  align-items: center;

  .countdown-text {
    margin-bottom: 18px;
    font-weight: 400;
    font-size: 32px;
    line-height: 52px;
  }

  .tip-text {
    height: 69px;
    padding: 0 54px;
    color: #000;
    font-size: 32px;
    background: #bcbcbc;
    border-radius: 50px;
    line-height: 69px;
    font-weight: 700;
    white-space: nowrap;
    margin-bottom: 18px;
  }

  .round-badge {
    font-size: 32px;
    line-height: 52px;
    font-weight: 700;
  }
}

.native-preview-area {
  flex: 1;
  min-height: 0;
  display: flex;
}

.native-preview-placeholder {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f5;
  border-radius: 16px;

  .placeholder-hint {
    color: #ccc;
    font-size: 20px;
    pointer-events: none;
  }
}

.error-tip {
  margin-top: 8px;
  padding: 8px 16px;
  background: #fff2f0;
  color: #ff4d4f;
  border-radius: 8px;
  font-size: 13px;
}

.session-actions {
  padding: 16px 0 8px;
  width: 100%;
  display: flex;
  justify-content: center;

  .review-actions {
    width: 100%;
    display: flex;
    justify-content: space-between;
  }
}

.action-btn {
  font-weight: 700;
  transition: all 0.2s;
  box-shadow: none;
  width: 340px;
  margin: 0;

  &:active {
    transform: scale(0.98);
  }
}

.capture-btn {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-weight: 700;
  transition: all 0.3s ease;

  &.counting {
    background: #ff4d4f;
    border-color: #ff4d4f;
    color: #fff;
    box-shadow: 0 4px 16px rgba(255, 77, 79, 0.4);
    animation: pulse 1s ease-in-out infinite;

    &:active {
      transform: scale(0.98);
    }
  }
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.75; }
}

.retry-btn {
  background: #fff;
  border-color: #ff9900;
  border: 1px solid #ff9900;

  &:active {
    background: #f5f5f5;
  }
}
</style>
