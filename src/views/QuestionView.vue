<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const currentQuestion = ref(1)

const questionTexts = [
  '你是否有吮吸手指的习惯？',
  '你是否会无意识地咬住或吸吮上唇或下唇？',
  '你是否有吐舌头或者舔牙齿的习惯？',
  '你是否有长期张嘴呼吸的习惯，特别是在睡着后？',
  '你是否长期只用一边牙齿咀嚼食物？',
  '你是否有啃铅笔、啃筷子等物品或者有咬手指的习惯？'
];

const currentQuestionText = computed(() => questionTexts[currentQuestion.value - 1]);
const answers = ref<number[]>([]);

const goBack = () => {
  if (currentQuestion.value > 1) {
    currentQuestion.value--;
    answers.value.length = currentQuestion.value;
  } else {
    router.back()
  }
}

const selectOption = (index: number) => {
  answers.value[currentQuestion.value - 1] = index;
  nextQuestion();
}

const nextQuestion = () => {
  if (currentQuestion.value < questionTexts.length) {
    currentQuestion.value++
  }
}

const goNext = () => {
  router.push('/capture-intro')
}
</script>

<template>
  <div class="question-page">
    <div class="question-content">
      <!-- 返回按钮 -->
      <button class="back-button" @click="goBack">
        <img src="@/assets/images/back-button.png" alt="返回" />
      </button>

      <!-- 问询标题 -->
      <div class="header">
        <h1 class="title">问询 {{ currentQuestion }}</h1>
        <p class="progress">( {{ currentQuestion }} / {{ questionTexts.length }} )</p>
      </div>

      <!-- 问题内容 -->
      <div class="content">
        <p class="question-text">{{ currentQuestionText }}</p>

        <!-- 选项按钮 -->
        <div class="options">
          <button class="option-button" @click="selectOption(0)">
            <span class="option-text">是，我是这样</span>
          </button>
          <button class="option-button" @click="selectOption(1)">
            <span class="option-text">不，这不是我</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="bottom-nav">
      <PrimaryButton v-show="currentQuestion === questionTexts.length" text="已完成，进入拍摄室！" @click="goNext" />
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.question-page {
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

.back-button {
  width: 45px;
  height: 45px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 24px;

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
}

.header {
  margin-top: 24px;
}

.title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin: 0;
}

.progress {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 18px;
  font-weight: 700;
  line-height: 2.22;
  color: #000;
  margin: 0;
  margin-top: 4px;
}

.content {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  margin-top: 40px;
}

.question-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 18px;
  font-weight: 400;
  line-height: 1.44;
  color: #000;
  margin: 0;
}

.options {
  display: flex;
  gap: 16px;
  margin-top: 75px;
}

.option-button {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: #D9D9D9;
  border-radius: 73.5px;
  cursor: pointer;
  transition: all 0.2s ease;
  width: 141px;
  height: 64.5px;

  &.selected {
    background: #4CAF50;
  }
}

.option-icon {
  width: 35px;
  height: 35px;
  object-fit: cover;
  margin-top: 8px;
}

.option-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.625;
  color: #000;
  margin-top: 2px;
}

.bottom-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  margin-top: 12px;
}
</style>
