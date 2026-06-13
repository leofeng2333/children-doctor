<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { DualCamera } from '@/plugins/dual-camera/src/index'
import type { DualCameraPhoto } from '@/plugins/dual-camera/src/definitions'
import { uploadPhotos } from '@/utils/service'
import { useAnalysisStore } from '@/stores'
import CaptureSession from '@/components/CaptureSession.vue'
import LogoText from '@/components/LogoText.vue'

const router = useRouter()

const confirmedSessions = ref<DualCameraPhoto[]>([])
/** 'capturing' | 'finalReview' | 'uploading' */
const captureState = ref<'capturing' | 'finalReview' | 'uploading'>('capturing')
/** 当前正在进行的拍摄轮次（1 或 2） */
const currentRound = ref(1)
const isUploading = ref(false)
const errorMsg = ref('')
const isPreviewReady = ref(false)

/** finalReview 的 2x2 网格标题：第1行=露齿拍、第2行=非露齿拍；第1列=正视图、第2列=右侧视图 */
const columnTitles: ReadonlyArray<ReadonlyArray<string>> = [
  ['露齿正视图', '露齿右侧视图'],
  ['非露齿正视图', '非露齿右侧视图'],
]

/** 展平所有照片：第1轮前置 → 第1轮后置 → 第2轮前置 → 第2轮后置 */
const allPhotos = computed(() =>
  confirmedSessions.value.flatMap((s) => [
    s.frontDisplayUrl || s.frontCameraUrl,
    s.backDisplayUrl || s.backCameraUrl,
  ]),
)

onMounted(async () => {
  try {
    await DualCamera.startPreview()
    isPreviewReady.value = true
    console.log('[CameraCapture] 预览已开启')
  } catch (e) {
    errorMsg.value = (e as Error).message
    console.error('[CameraCapture] startPreview failed:', e)
  }
})

onUnmounted(async () => {
  if (isPreviewReady.value) {
    try {
      await DualCamera.stopPreview()
    } catch (_) {}
    isPreviewReady.value = false
  }
})

/**
 * CaptureSession 内点击"确认"：本轮照片确认，进入下一轮或最终确认
 * - 第1次确认：继续第2轮拍摄
 * - 第2次确认：展示全部4张照片
 */
const handleConfirmed = (photo: DualCameraPhoto) => {
  console.log('[CameraCapture] handleConfirmed')
  confirmedSessions.value.push(photo)
  console.log('[CameraCapture] confirmedSessions.length:', confirmedSessions.value.length)
  if (confirmedSessions.value.length >= 2) {
    captureState.value = 'finalReview'
    // 进入最终确认时停止摄像头预览
    DualCamera.stopPreview().catch(() => {})
    console.log('[CameraCapture] finalReview, stopped preview')
  } else {
    currentRound.value++
    console.log('[CameraCapture] 进入 round', currentRound.value)
  }
}

/** finalReview 页面点击"下一步" */
const handleFinalConfirmed = () => {
  console.log('[CameraCapture] handleFinalConfirmed')
  startAnalysis()
}

/** finalReview 页面点击"重新拍摄" */
const handleRetake = () => {
  console.log('[CameraCapture] handleRetake')
  confirmedSessions.value = []
  currentRound.value = 1
  captureState.value = 'capturing'
  DualCamera.startPreview()
    .then(() => {
      isPreviewReady.value = true
    })
    .catch((e) => {
      errorMsg.value = (e as Error).message
      console.error('[CameraCapture] startPreview failed:', e)
    })
}

const startAnalysis = async () => {
  if (isUploading.value) return
  isUploading.value = true
  errorMsg.value = ''
  try {
    console.log('[CameraCapture] 开始分析, 照片组数:', confirmedSessions.value.length)
    const uploadResult = await uploadPhotos(confirmedSessions.value)
    console.log('[CameraCapture] 上传结果:', uploadResult)
    const analysisStore = useAnalysisStore()
    analysisStore.start()
    await router.push({ path: '/question' })
  } catch (e) {
    errorMsg.value = (e as Error).message
    console.error('[CameraCapture] 开始分析失败:', e)
  } finally {
    isUploading.value = false
  }
}
</script>

<template>
  <div class="capture-page">
    <!-- 主内容区 -->
    <div class="capture-content">
      <div v-if="errorMsg" class="error-tip">{{ errorMsg }}</div>
      <!-- CaptureSession 仅在 capturing 阶段显示，内部自行管理 pendingPhoto 状态（重拍/确认） -->
      <CaptureSession
        v-if="isPreviewReady && captureState === 'capturing'"
        :round="currentRound"
        @confirmed="handleConfirmed"
      />
      <!-- finalReview：全部4张照片，按 2x2 网格分组（行=露齿/非露齿，列=正视图/右侧视图） -->
      <div v-if="captureState === 'finalReview'" class="review-state">
        <div v-for="(row, rowIndex) in columnTitles" :key="rowIndex" class="photo-row">
          <div
            v-for="(title, columnIndex) in row"
            :key="`${rowIndex}-${columnIndex}`"
            class="photo-column"
          >
            <div class="column-title">{{ title }}</div>
            <div class="column-photo">
              <img
                class="photo-img"
                :src="allPhotos[rowIndex * 2 + columnIndex]"
                :alt="`${title} ${rowIndex + 1}`"
              />
            </div>
          </div>
        </div>
      </div>
      <!-- uploading -->
      <!-- v-else-if="captureState === 'uploading'" -->
      <!-- <div class="uploading-state">
        <div class="uploading-icon"></div>
        <p class="uploading-text">正在上传...</p>
        <p class="uploading-sub">请稍候</p>
      </div> -->
    </div>

    <!-- 底部区域 -->
    <div class="bottom-section">
      <!-- finalReview: 两个按钮 -->
      <primary-button
        v-if="captureState === 'finalReview'"
        @click="handleFinalConfirmed"
        text="开始分析"
      ></primary-button>
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.capture-page {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #ffffff;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(80px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
}

.breadcrumb {
  font-size: 24px;
  color: #999;
  margin-bottom: 24px;
}

.page-title {
  font-size: 56px;
  font-weight: 700;
  color: #1a1a1a;
  margin: 0 0 32px 0;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
}

.capture-content {
  position: relative;
  flex: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.error-tip {
  margin-bottom: 8px;
  padding: 8px 16px;
  background: #fff2f0;
  color: #ff4d4f;
  border-radius: 8px;
  font-size: 13px;
}

.uploading-state {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;

  .uploading-icon {
    width: 48px;
    height: 48px;
    border: 3px solid #ff9900;
    border-top-color: transparent;
    border-radius: 50%;
    animation: spin 0.8s linear infinite;
    margin-bottom: 16px;
  }

  .uploading-text {
    font-size: 20px;
    color: #000;
    font-weight: 700;
  }

  .uploading-sub {
    margin-top: 8px;
    font-size: 14px;
    color: #999;
  }
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.review-state {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 24px;

  .photo-row {
    display: flex;
    flex-direction: row;
    justify-content: space-between;
  }

  .photo-column {
    width: 331px;
    display: flex;
    flex-direction: column;
    min-height: 0;

    .column-title {
      font-size: 32px;
      font-weight: 400;
      line-height: 52px;
      margin-bottom: 8px;
      text-align: center;
    }

    .column-photo {
      display: flex;
      flex-direction: column;
      width: 100%;
      height: 515px;
      gap: 16px;

      .photo-img {
        width: 100%;
        height: 100%;
        object-fit: cover;
        background: #f5f5f5;
      }
    }
  }
}

.action-row {
  width: 100%;
  display: flex;
  flex-direction: row;
  gap: 24px;

  .primary-btn {
    flex: 1;
    background: #ff9900;
    color: #fff;
    font-family:
      'Inter',
      -apple-system,
      BlinkMacSystemFont,
      sans-serif;
    font-size: 32px;
    font-weight: 700;
    border: none;
    border-radius: 50px;
    padding: 28px 60px;
    cursor: pointer;
    box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
    transition: all 0.2s;

    &:active {
      transform: scale(0.98);
      box-shadow: 0 4px 12px rgba(255, 153, 0, 0.3);
    }
  }

  .secondary-btn {
    flex: 1;
    background: #ffffff;
    color: #1a1a1a;
    font-family:
      'Inter',
      -apple-system,
      BlinkMacSystemFont,
      sans-serif;
    font-size: 32px;
    font-weight: 700;
    border: 2px solid #1a1a1a;
    border-radius: 50px;
    padding: 28px 60px;
    cursor: pointer;
    transition: all 0.2s;

    &:active {
      transform: scale(0.98);
      background: #f5f5f5;
    }
  }
}

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;

  .logo {
    margin-top: 20px;
  }
}
</style>
