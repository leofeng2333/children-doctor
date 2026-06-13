<script setup lang="ts">
import { useRouter } from 'vue-router'
import LogoText from '@/components/LogoText.vue'
import { useUserStore } from '@/stores'
import { saveUserInfo } from '@/utils/service'

const router = useRouter()

const handleSelect = async (isOutOfProvince: boolean) => {
  if (!isOutOfProvince) {
    router.push('/map')
    return
  }
  const { nickname, phone } = useUserStore()
  const params: Record<string, string> = {
    nickname,
    phone,
    province: isOutOfProvince ? '浙江省外' : '浙江省',
  }
  await saveUserInfo(params)
  router.push('/diagnosis')
}
</script>

<template>
  <div class="form-container">
    <div class="page-top-container">
      <div class="welcome-text">Hi，<br />我是你的AI口腔医生！</div>
      <h1 class="form-title">我们先来填写用户的问诊单吧。</h1>

      <div class="page-content">
        <h2 class="location-title">你现居住在哪里？</h2>

        <div class="location-selects">
          <div class="location-select-item" @click="handleSelect(false)">我在浙江省内</div>
          <div class="location-select-item" @click="handleSelect(true)">我在浙江省外</div>
        </div>
      </div>
    </div>

    <div class="bottom-section-buttons">
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped>
.form-container {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #ffffff;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(42px + env(safe-area-inset-bottom));
  overflow-y: auto;
  justify-content: space-between;
}

.welcome-text {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
}

.form-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 16px 0 30px 0;
}

.page-content {
  margin-top: 20px;
}

.location-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin-bottom: 12px;
}

.location-selects {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 114px;

  .location-select-item {
    width: 467px;
    height: 85px;
    border-radius: 50px;
    font-weight: 700;
    font-size: 32px;
    line-height: 85px;
    background-color: #d9d9d9;
    text-align: center;

    &:first-child {
      margin-bottom: 48px;
    }
  }
}

.bottom-section-buttons {
  display: flex;
  flex-direction: column;
  align-items: center;

  .logo {
    margin-top: 20px;
  }
}
</style>
