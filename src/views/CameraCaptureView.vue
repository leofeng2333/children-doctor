<script lang="ts" setup>
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router'

const router = useRouter()

const photos = ref<string[]>([]);
const hasCaptured = computed(() => photos.value.length > 0);

const hanleCapture = () => {
}

const hanleStartAnalysis = () => {
  router.push('/analysing');
}
</script>

<template>
  <div class="capture-page">
    <div class="capture-content">
      <div class="title-tip">请正面看向镜头</div>
    </div>
    <div class="bottom-section">
      <PrimaryButton v-if="!hasCaptured" text="咔嚓！" @click="hanleCapture" />
      <PrimaryButton v-else text="开始分析" @click="hanleCapture" />
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

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  margin-top: 12px;
}
</style>
