<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
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

async function onPrint() {
  if (isPrinting.value || hasPrinted.value) return
  isPrinting.value = true
  printError.value = ''
  try {
    await printPhoto({
      goodImgUrl: analysisSuccess,
      qrcodeUrl: '',
      jobName: '打印测试照片',
    })
    hasPrinted.value = true
  } catch (e) {
    printError.value = (e as Error)?.message ?? '打印失败'
  } finally {
    isPrinting.value = false
  }
}
</script>

<template>
  <div class="print-test-page">
    <button class="back-btn" @click="goBack">← 返回首页</button>

    <h1 class="title">打印功能验证</h1>
    <p class="subtitle">调用入口与详情页 ScanSubscription 完全一致</p>

    <button class="print-btn" :disabled="isPrinting || hasPrinted" @click="onPrint">
      {{ hasPrinted ? '已打印完成' : isPrinting ? '正在准备打印…' : '打印测试照片' }}
    </button>

    <p v-if="printError" class="print-error">{{ printError }}</p>
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

.print-error {
  font-size: 18px;
  color: #ff4d4f;
  text-align: center;
}
</style>
