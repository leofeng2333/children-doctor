<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
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

/**
 * 把一条消息同时写到 console 和 native 日志文件。
 * 日志会话的 start/close 全部在 native 端（DualCameraPlugin）管理：
 *   - start: startPreview() 调用之后自动 startSession()
 *   - close: shutdownManager() / handleOnPause() / handleOnDestroy() 三处兜底
 * 这里只负责把消息镜像到 native 文件。
 */
const log = (msg: string) => {
  console.log('[CaptureSession] ' + msg)
  if (isNative) {
    DualCamera.captureLog({ tag: 'CaptureSession', msg }).catch(() => {
      /* swallow：日志写失败不影响主流程 */
    })
  }
}

const isNative = Capacitor.isNativePlatform()

onMounted(() => {
  log(`mounted, round=${props.round}`)
})

onBeforeUnmount(() => {
  log(`unmounted, pendingPhoto=${pendingPhoto.value ? 'set' : 'null'}`)
  clearTimers()
})

const startCountdown = () => {
  isCounting.value = true
  countdown.value = COUNTDOWN_SECONDS
  errorMsg.value = ''
  log(`countdown START, round=${props.round}, ${COUNTDOWN_SECONDS}s`)

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
  log('countdown CANCELED')
}

const doCapture = async () => {
  log('doCapture START')
  isCounting.value = false
  isCapturing.value = true
  try {
    const result = (await DualCamera.capture()) as Record<string, unknown>
    log(`capture() resolved, keys=${Object.keys(result).join(',')}`)
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

    log(`copy front: ${frontUrl}, back: ${backUrl}`)
    const [frontResult, backResult] = await Promise.all([
      DualCamera.copyImageToExternalCache({ uri: frontUrl }),
      DualCamera.copyImageToExternalCache({ uri: backUrl }),
    ])
    log(`copy results: front=${frontResult.path}, back=${backResult.path}`)
    photo.frontDisplayUrl = Capacitor.convertFileSrc(frontResult.uri)
    photo.backDisplayUrl = Capacitor.convertFileSrc(backResult.uri)

    log(`doCapture OK, round=${props.round}, fileSize front=${result.cameraFileSize0}KB back=${result.cameraFileSize1}KB`)

    pendingPhoto.value = photo
    log('pendingPhoto.value ASSIGNED')
  } catch (e) {
    const err = (e as Error).message
    errorMsg.value = err
    log(`doCapture FAILED: ${err}`)
    console.error('[CaptureSession] capture failed:', e)
  } finally {
    isCapturing.value = false
    countdown.value = COUNTDOWN_SECONDS
    log(`doCapture END, pendingPhoto=${pendingPhoto.value ? 'set' : 'null'}, errorMsg=${errorMsg.value || 'none'}`)
  }
}

const handleCaptureClick = () => {
  if (isCapturing.value) {
    log('handleCaptureClick ignored (capturing in progress)')
    return
  }
  if (isCounting.value) {
    cancelCountdown()
    return
  }
  log(`handleCaptureClick, round=${props.round}`)
  startCountdown()
}

const handleRetry = async () => {
  log('retry click')
  pendingPhoto.value = null
  countdown.value = COUNTDOWN_SECONDS
  try {
    await DualCamera.resumePreviewFromPhotos()
  } catch (_) { }
}

const handleConfirm = async () => {
  if (!pendingPhoto.value) return
  log(`confirm click, round=${props.round}`)
  try {
    await DualCamera.resumePreviewFromPhotos()
  } catch (_) { }
  emit('confirmed', pendingPhoto.value)
  pendingPhoto.value = null
}
</script>

<template>
  <div class="capture-session">
    <!-- 顶部提示 -->
    <div v-if="pendingPhoto" class="confirm-tip">
      <img src="@/assets/images/common-left.png" alt="" class="confirm-tip-icon" aria-hidden="true" />
      <span>请确认你的照片</span>
    </div>
    <div v-else class="session-tip">
      <img src="@/assets/images/common-left.png" alt="" class="session-tip-icon" aria-hidden="true" />
      <div class="round-badge-container">
        <div v-if="round === 1 && !pendingPhoto" class="round-badge">请正视屏幕左侧的镜头，让自己的面部居于虚线框中<br />记得开心地露出牙齿拍摄哦！</div>
        <div v-if="round === 2 && !pendingPhoto" class="round-badge">请正视屏幕左侧的镜头，让自己的面部居于虚线框中<br />合上小嘴巴，让我来看看面部！</div>
      </div>
      <span class="countdown-text">{{ countdownText }}</span>
    </div>

    <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>

    <!-- 操作区 -->
    <div class="session-actions">
      <template v-if="!pendingPhoto">
        <PrimaryButton class="capture-btn" :class="{ counting: isCounting, capturing: isCapturing }"
          :disabled="isCapturing" @click="handleCaptureClick">
          <span v-if="isCapturing">拍摄中...</span>
          <span v-else-if="isCounting">取消 <span class="countdown-num">{{ countdown }}</span></span>
          <span v-else>开始拍摄，进入倒计时</span>
        </PrimaryButton>
      </template>
      <div v-else class="review-actions">
        <PrimaryButton class="action-btn confirm-btn" @click="handleConfirm">确认照片，下一步</PrimaryButton>
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
  display: flex;
  align-items: center;
  justify-content: center;
  padding-top: max(80px, env(safe-area-inset-top));
  padding-left: 90px;
  padding-right: 90px;
}

.confirm-tip-icon {
  width: 140px;
  height: 150px;
  object-fit: contain;
  margin-right: 24px;
}

.session-tip {
  display: flex;
  flex-direction: column;
  align-items: center;

  position: relative;

  .session-tip-icon {
    width: 140px;
    height: 150px;
    object-fit: contain;
    margin-right: 24px;
    position: absolute;
    top: 155px;
    left: 52px;
  }

  .round-badge-container {
    padding-top: max(64px, env(safe-area-inset-top));
    padding-left: 80px;
    padding-right: 80px;
    padding-bottom: 24px;
    background-color: #FFE361;
    width: 100%;
  }

  .countdown-text {
    font-weight: 400;
    font-size: 40px;
    line-height: 52px;
    font-weight: 700;
    margin-top: 30px;
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
    text-align: center;
    font-size: 32px;
    line-height: 52px;
    font-weight: 700;
    margin-bottom: 18px;

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
  padding: 16px 90px calc(40px + env(safe-area-inset-bottom));
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

  .countdown-num {
    display: inline-block;
    font-size: 1.25em;
    font-weight: 800;
    margin-left: 4px;
    min-width: 1em;
    text-align: center;
  }

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

  0%,
  100% {
    opacity: 1;
  }

  50% {
    opacity: 0.75;
  }
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
