<script setup lang="ts">
import { useRouter } from 'vue-router'
import PrimaryButton from '@/components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'
import { quizQuestions } from '@/data/quizQuestions'

const router = useRouter()
const totalQuestions = quizQuestions.length

const goToQuiz = () => {
  router.push({ name: 'quiz' })
}

const goBack = () => {
  router.back()
}
</script>

<template>
  <div class="quiz-intro-page">
    <div class="quiz-intro-content">
      <h1 class="title">智慧问答</h1>
      <p class="desc">即将进入答题环节</p>
    </div>

    <div class="bottom-section">
      <PrimaryButton text="开始答题" @click="goToQuiz" />
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.quiz-intro-page {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #ffffff;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
  justify-content: space-between;
}

.quiz-intro-content {
  flex: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  margin-top: 88px;
}

.title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
  margin: 0;
}

.desc {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 36px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 30px 0 0 0;
}

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 60px;
}

.back-link {
  margin-top: 24px;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 24px;
  font-weight: 400;
  color: #666;
  cursor: pointer;
  user-select: none;
  transition: color 0.2s ease;

  &:active {
    color: #000;
  }
}

.logo {
  margin-top: 20px;
}
</style>
