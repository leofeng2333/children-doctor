<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Capacitor } from '@capacitor/core';
import { DualCamera } from '@/plugins/dual-camera/src/index';
import type {
  DualCameraPhoto,
  DualCameraPreviewResult,
  DualCameraDeviceCamera,
} from '@/plugins/dual-camera/src/definitions';
import { startAnalysis, uploadPhotos } from '@/utils/service';

interface DeviceInfo {
  webViewVersion: string;
  webViewEngine: string;
}

function getDeviceInfo(): DeviceInfo {
  const ua = navigator.userAgent;
  const chromeMatch = ua.match(/Chrome\/([\d.]+)/);
  return {
    webViewVersion: chromeMatch?.[1] ?? 'unknown',
    webViewEngine: 'chromium',
  };
}

const router = useRouter();

const photos = ref<DualCameraPhoto[]>([]);
const hasCaptured = computed(() => photos.value.length > 0);
const isPreviewActive = ref(false);
const isCapturing = ref(false);
const isUploading = ref(false);
const errorMsg = ref('');
const isConcurrent = ref(false);
const cameraCount = ref(0);
const supportsConcurrent = ref(false);
const availableCameras = ref<DualCameraDeviceCamera[]>([]);
const activeCameras = ref<DualCameraDeviceCamera[]>([]);

onMounted(async () => {
  errorMsg.value = '';

  try {
    const supportResult = await DualCamera.isDualCameraSupported();
    supportsConcurrent.value = supportResult.supported;
    console.log('[DualCamera] 支持并发双摄:', supportResult.supported);

    const camerasResult = await DualCamera.getAvailableCameras();
    availableCameras.value = camerasResult.cameras;
    cameraCount.value = camerasResult.cameras.length;
    console.log('[DualCamera] 可用摄像头:', camerasResult.cameras);
  } catch (e) {
    console.warn('[DualCamera] 获取摄像头信息失败:', e);
  }

  try {
    const result = await DualCamera.startPreviewWithPermission() as DualCameraPreviewResult;
    isPreviewActive.value = true;
    isConcurrent.value = result.concurrent;
    activeCameras.value = result.cameras;
    console.log('[DualCamera] 预览开启, 并发模式:', result.concurrent, '摄像头:', result.cameras);
  } catch (e) {
    errorMsg.value = (e as Error).message;
    console.error('[DualCamera] startPreview failed:', e);
  }
});

onUnmounted(async () => {
  if (isPreviewActive.value) {
    try {
      await DualCamera.stopPreview();
    } catch (_) { }
    isPreviewActive.value = false;
  }
});

const handleCapture = async () => {
  if (isCapturing.value) return;
  isCapturing.value = true;
  errorMsg.value = '';
  try {
    const result = await DualCamera.capture();
    photos.value.push({
      ...result,
      // @ts-ignore
      frontCameraUrl: result.cameraPath0,
      // @ts-ignore
      backCameraUrl: result.cameraPath1,
    });
    console.log('[DualCamera] 拍照成功:', result);
  } catch (e) {
    errorMsg.value = (e as Error).message;
    console.error('[DualCamera] capture failed:', e);
  } finally {
    isCapturing.value = false;
  }
};

const handleStartAnalysis = async () => {
  if (isUploading.value) return;
  isUploading.value = true;
  errorMsg.value = '';
  try {
    // console.log('[CameraCapture] 开始分析, 照片数量:', photos.value.length);
    // const uploadResult = await uploadPhotos(photos.value);
    // console.log('[CameraCapture] 上传结果:', uploadResult);
    const analysisResult = (await startAnalysis()) as { llmAnalysis: { taskId: string } };
    console.log('[CameraCapture] 启动分析:', analysisResult);
    const taskId = analysisResult.llmAnalysis.taskId
    await router.push({
      path: '/detail-analysis',
      query: {
        taskId
      }
    });
  } catch (e) {
    errorMsg.value = (e as Error).message;
    console.error('[CameraCapture] 开始分析失败:', e);
  } finally {
    isUploading.value = false;
  }
};
</script>

<template>
  <div class="capture-page">
    <div class="capture-content">

      <div class="title-tip">
        请正面看向镜头
      </div>
    </div>

    <div class="bottom-section">
      <!-- <PrimaryButton v-if="!hasCaptured" text="咔嚓！" :disabled="isCapturing || !!errorMsg" @click="handleCapture" />
      <PrimaryButton v-else text="开始分析" :disabled="isUploading" :loading="isUploading" @click="handleStartAnalysis" /> -->
      <PrimaryButton text="开始分析" @click="handleStartAnalysis" />
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

  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 30px;
  padding-top: max(42px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
  justify-content: space-between;
}

.capture-content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.debug-panel {
  width: 100%;
  margin-bottom: 12px;
  padding: 10px 12px;
  background: #f5f5f5;
  border-radius: 8px;
  font-size: 12px;
  font-family: 'Courier New', monospace;
}

.debug-divider {
  border-top: 1px solid #ddd;
  margin: 6px 0;
}

.debug-title {
  font-weight: 700;
  margin-bottom: 6px;
  color: #333;
}

.debug-row {
  display: flex;
  justify-content: space-between;
  padding: 2px 0;
  color: #555;

  &.debug-sub {
    padding-left: 12px;
    font-size: 11px;
    color: #777;
  }
}

.debug-label {
  color: #666;
}

.debug-value {
  color: #333;
  font-weight: 500;

  &.ok {
    color: #52c41a;
  }

  &.warn {
    color: #fa8c16;
  }
}

.title-tip {
  padding: 9px 54px;
  color: #000;
  font-size: 32px;
  background: #bcbcbc;
  border-radius: 50px;
  line-height: 52px;
  box-sizing: border-box;
  min-width: 48px;
  font-weight: 700;
}

.error-tip {
  margin-top: 16px;
  padding: 8px 16px;
  background: #fff2f0;
  color: #ff4d4f;
  border-radius: 8px;
  font-size: 13px;
}

.photo-previews {
  margin-top: 20px;
  width: 100%;
  overflow-y: auto;
  max-height: 50vh;
}

.photo-pair {
  display: flex;
  gap: 12px;
  margin-bottom: 12px;
  background: #fafafa;
  border-radius: 12px;
  padding: 8px;
}

.waiting-tip {
  margin-top: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  color: #666;
  font-size: 14px;

  .sub {
    margin-top: 8px;
    font-size: 12px;
    color: #999;
  }
}

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  margin-top: 12px;
}
</style>
