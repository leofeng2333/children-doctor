<script lang="ts" setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const hanleCapture = () => {
}
</script>

<template>
  <div class="capture-page">
    <div class="capture-content">

    </div>
    <div class="bottom-section">
      <PrimaryButton text="咔嚓！" @click="hanleCapture" />
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
