<script setup lang="ts">
import { computed, ref } from 'vue'
import LogoText from '@/components/LogoText.vue'
import { quizQuestions } from '@/data/quizQuestions'

const currentIndex = ref(0)
const currentQuestion = computed(() => {
  const q = quizQuestions[currentIndex.value]
  if (!q) {
    throw new Error('current question missing')
  }
  return q
})
const selectedOption = ref<string | null>(null)

const selectOption = (label: string) => {
  selectedOption.value = label
}
</script>

<template>
  <div class="quiz-page">
    <div class="quiz-content">

      <!-- 标题 -->
      <h1 class="title">智慧问答</h1>

      <!-- 题目 -->
      <p class="question-text">{{ currentQuestion.question }}</p>

      <!-- 选项区：2x2 圆角卡片 -->
      <div class="options-grid">
        <div v-for="opt in currentQuestion.options" :key="opt.label" class="option-cell">
          <span class="option-label">{{ opt.label }}</span>
          <button class="option-card" :class="{ selected: selectedOption === opt.label }"
            @click="selectOption(opt.label)">
            <span class="option-text">{{ opt.text }}</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 底部区：logo + 下一个按钮 -->
    <div class="bottom-section">
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.quiz-page {
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

.quiz-content {
  flex: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.top-header {
  position: relative;
  width: 240px;
  height: 90px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-strip {
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  width: 240px;
  height: 37px;
  background: #bcbcbc;
  border-radius: 4px;
}

.logo-text {
  position: relative;
  z-index: 1;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 24px;
  font-weight: 400;
  line-height: 90px;
  color: #000;
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
  margin-top: 88px;
}

.question-text {
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

.options-grid {
  display: grid;
  grid-template-columns: 282px 282px;
  column-gap: 64px;
  row-gap: 48px;
  margin-top: 75px;
  justify-content: center;
}

.option-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.option-label {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 700;
  line-height: 52px;
  color: #000;
  margin-bottom: 0;
}

.option-card {
  background: #d9d9d9;
  border: none;
  border-radius: 73.5px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  padding: 0;
  width: 282px;
  height: 129px;

  &.selected {
    background: #4caf50;
  }

  &:active {
    transform: scale(0.98);
  }
}

.option-text {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 700;
  line-height: 52px;
  color: #000;
}

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-top: 60px;
}

.logo {
  margin-top: 20px;
}
</style>
