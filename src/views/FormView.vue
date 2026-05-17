<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'
import { useUserStore } from '@/stores/user'

const router = useRouter()

const userStore = useUserStore()

const phoneError = ref('')
const nicknameError = ref('')

const validateNickname = (value: string) => {
  if (!value.trim()) {
    nicknameError.value = '请输入用户称呼'
    return false
  }
  nicknameError.value = ''
  return true
}

const validatePhone = (value: string) => {
  const trimmed = value.trim()
  if (!trimmed) {
    phoneError.value = ''
    return false
  }
  const phoneRegex = /^1[3-9]\d{9}$/
  if (!phoneRegex.test(trimmed)) {
    phoneError.value = '请输入正确的11位手机号'
    return false
  }
  phoneError.value = ''
  return true
}

const btnDisabled = computed(() => {
  return !userStore.nickname.trim() || !userStore.phone.trim()
})

watch(() => userStore.phone, (newVal) => {
  validatePhone(newVal)
})

watch(() => userStore.nickname, (newVal) => {
  validateNickname(newVal)
})

const goNext = () => {
  if (!validateNickname(userStore.nickname)) {
    return;
  }
  if (!validatePhone(userStore.phone)) {
    return;
  }
  router.push('/map')
  userStore.update(userStore.nickname.trim(), userStore.phone.trim())
}
</script>

<template>
  <div class="form-container">

    <!-- 欢迎语 -->
    <div class="welcome-text">
      Hi，<br />我是你的AI口腔医生！
    </div>

    <!-- 标题 -->
    <h1 class="form-title">我们先来填写用户的问诊单吧。</h1>

    <!-- 表单 -->
    <div class="form-content">
      <!-- 名字输入 -->
      <div class="input-group">
        <label class="input-label">我该怎么称呼你呢？</label>
        <input v-model="userStore.nickname" type="text" :maxlength="20" class="input-field"
          :class="{ 'input-field-error': nicknameError }" placeholder="请输入用户全名/昵称" />
        <p v-if="nicknameError" class="error-text">{{ nicknameError }}</p>
      </div>
      <!-- 电话输入 -->
      <div class="input-group">
        <label class="input-label">你的联系方式？</label>
        <input v-model="userStore.phone" type="tel" class="input-field" :class="{ 'input-field-error': phoneError }"
          placeholder="请输入手机号" />
        <p v-if="phoneError" class="error-text">{{ phoneError }}</p>
      </div>
    </div>

    <!-- 按钮 -->
    <div class="bottom-section-buttons">
      <!-- 按钮 -->
      <PrimaryButton text="下一步" :disabled="btnDisabled" @click="goNext" />

      <!-- Logo -->
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

  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
}

/* Logo */
.logo-text {
  text-align: center;
  font-family: 'Inter', sans-serif;
  font-size: 24px;
  color: #BCBCBC;
}

/* 欢迎语 */
.welcome-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
}

/* 标题 */
.form-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 16px 0 60px 0;
}

/* 表单 */
.form-content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
}

.input-group {
  margin-bottom: 40px;
}

.input-label {
  display: block;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  line-height: 52px;
  font-weight: 400;
  color: #000;
  margin-bottom: 12px;
}

.input-field {
  width: 100%;
  height: 75px;
  background: #D9D9D9;
  border: none;
  border-radius: 12px;
  padding: 0 24px;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 20px;
  color: #000;
}

.input-field::placeholder {
  color: #BCBCBC;
}

.input-field:focus {
  outline: none;
  background: #E5E5E5;
}

.input-field-error {
  background: #FFE4E4 !important;
  border: 2px solid #FF4D4F;
}

.error-text {
  font-size: 18px;
  color: #FF4D4F;
  margin: 8px 0 0 0;
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
