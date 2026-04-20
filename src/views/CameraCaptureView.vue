<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Capacitor } from '@capacitor/core';
import { DualCamera } from '@/plugins/dual-camera/src/index';
import type { DualCameraPhoto } from '@/plugins/dual-camera/src/definitions';
import { startAnalysis, uploadPhotos } from '@/utils/service';

const router = useRouter();

const photos = ref<DualCameraPhoto[]>([]);
const hasCaptured = computed(() => photos.value.length > 0);
const isPreviewActive = ref(false);
const isCapturing = ref(false);
const isUploading = ref(false);
const errorMsg = ref('');

onMounted(async () => {
  errorMsg.value = '';
  try {
    await DualCamera.startPreviewWithPermission();
    isPreviewActive.value = true;
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
      frontCameraUrl: Capacitor.convertFileSrc(result.frontCameraUrl),
      backCameraUrl: Capacitor.convertFileSrc(result.backCameraUrl),
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
    console.log('[CameraCapture] 开始分析, 照片数量:', photos.value.length);
    const uploadResult = await uploadPhotos(photos.value);
    console.log('[CameraCapture] 上传结果:', uploadResult);
    const analysisResult = (await startAnalysis()) as { llmAnalysis: { taskId: string } };
    console.log('[CameraCapture] 启动分析:', analysisResult);
    const taskId = analysisResult.llmAnalysis.taskId
    await router.push({
      path: '/detail-analysis?taskId=' + taskId,
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
      <PrimaryButton v-if="!hasCaptured" text="咔嚓！" :disabled="isCapturing || !!errorMsg" @click="handleCapture" />
      <PrimaryButton v-else text="开始分析" :disabled="isUploading" :loading="isUploading" @click="handleStartAnalysis" />
      <LogoText class="logo" />
    </div>
  </div>
</template>


<style scoped lang="scss">
.capture-page {
  height: 100vh;
  height: 100dvh;
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
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.title-tip {
  padding: 12px 24px;
  color: #000;
  font-size: 14px;
  background: #bcbcbc;
  border-radius: 50px;
  line-height: 1;
  box-sizing: border-box;
  min-height: 32px;
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

.photo-item {
  flex: 1;
  text-align: center;

  .label {
    display: inline-block;
    padding: 2px 8px;
    background: #1890ff;
    color: #fff;
    border-radius: 4px;
    font-size: 11px;
    margin-bottom: 4px;
  }

  img {
    width: 100%;
    max-width: 160px;
    height: auto;
    border-radius: 6px;
    border: 1px solid #eee;
  }
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
