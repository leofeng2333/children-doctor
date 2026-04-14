<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'

const router = useRouter()

const name = ref('')
const phone = ref('')

const goNext = () => {
  if (name.value.trim() && phone.value.trim()) {
    router.push('/')
  }
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
        <input v-model="name" type="text" class="input-field" placeholder="请输入用户全名/昵称" />
      </div>

      <!-- 电话输入 -->
      <div class="input-group">
        <label class="input-label">你的联系方式？</label>
        <input v-model="phone" type="tel" class="input-field" placeholder="请输入手机号" />
      </div>
    </div>

    <!-- 按钮 -->
    <div class="bottom-section-buttons">
      <!-- 按钮 -->
      <PrimaryButton text="下一步" :disabled="!name.trim() || !phone.trim()" @click="goNext" />

      <!-- Logo -->
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped>
.form-container {
  height: 100dvh;
  height: 100vh;
  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 30px;
  padding-top: max(42px, env(safe-area-inset-top));
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
  font-size: 30px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin-top: 24px;
}

/* 标题 */
.form-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.625;
  color: #000;
  margin: 16px 0 60px 0;
}

/* 表单 */
.form-content {
  flex: 1;
}

.input-group {
  margin-bottom: 40px;
}

.input-label {
  display: block;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  color: #000;
  margin-bottom: 12px;
}

.input-field {
  width: 100%;
  height: 50px;
  background: #D9D9D9;
  border: none;
  border-radius: 12px;
  padding: 0 24px;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  color: #000;
}

.input-field::placeholder {
  color: #BCBCBC;
}

.input-field:focus {
  outline: none;
  background: #E5E5E5;
}

.bottom-section-buttons {
  display: flex;
  flex-direction: column;
  align-items: center;

  .logo {
    margin-top: 12px;
  }
}
</style>
