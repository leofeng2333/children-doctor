<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const currentQuestion = ref(1)
const totalQuestions = 3

const questions = [
  {
    text: '你是否有吮吸手指的习惯？',
    options: [
      { text: '是，我是这样', icon: '@/assets/images/mouth-1.png', selected: false },
      { text: '不，这不是我', icon: '@/assets/images/mouth-2.png', selected: false }
    ]
  },
  {
    text: '你是否有口呼吸的习惯？',
    options: [
      { text: '是，我是这样', icon: '@/assets/images/mouth-1.png', selected: false },
      { text: '不，这不是我', icon: '@/assets/images/mouth-2.png', selected: false }
    ]
  },
  {
    text: '你的牙齿是否整齐？',
    options: [
      { text: '是，我的牙齿整齐', icon: '@/assets/images/smile-1.png', selected: false },
      { text: '不，我的牙齿不整齐', icon: '@/assets/images/smile-2-21e83d.png', selected: false }
    ]
  }
]

const selectedOption = ref<number | null>(null)

const goBack = () => {
  if (currentQuestion.value > 1) {
    currentQuestion.value--
    selectedOption.value = null
  } else {
    router.back()
  }
}

const selectOption = (index: number) => {
  selectedOption.value = index
}

const nextQuestion = () => {
  if (currentQuestion.value < totalQuestions) {
    currentQuestion.value++
    selectedOption.value = null
  }
}
</script>

<template>
  <div class="question-page">
    <!-- 返回按钮 -->
    <button class="back-button" @click="goBack">
      <img src="@/assets/images/back-button.png" alt="返回" />
    </button>

    <!-- 问询标题 -->
    <div class="header">
      <h1 class="title">问询 {{ currentQuestion }}</h1>
      <p class="progress">( {{ currentQuestion }} / {{ totalQuestions }} )</p>
    </div>

    <!-- 问题内容 -->
    <div class="content">
      <p class="question-text">{{ questions[currentQuestion - 1].text }}</p>

      <!-- 选项按钮 -->
      <div class="options">
        <button v-for="(option, index) in questions[currentQuestion - 1].options" :key="index" class="option-button"
          :class="{ selected: selectedOption === index }" @click="selectOption(index)">
          <img :src="option.icon" :alt="option.text" class="option-icon" />
          <span class="option-text">{{ option.text }}</span>
        </button>
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="bottom-nav">
      <button class="next-button" :disabled="selectedOption === null" @click="nextQuestion">
        <span>下一个</span>
        <img src="@/assets/images/arrow-left.png" alt="下一个" class="arrow-icon" />
      </button>
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
  padding: 0 36px;
  padding-top: max(42px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
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

  img {
    width: 100%;
    height: 100%;
    object-fit: contain;
  }
}

.header {
  margin-top: 55px;
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
  margin-top: 70px;
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
  flex-direction: column;
  gap: 16px;
  margin-top: 75px;
}

.option-button {
  display: flex;
  flex-direction: column;
  align-items: center;
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
  justify-content: flex-end;
  align-items: center;
}

.next-button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;

  span {
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
    font-size: 12px;
    font-weight: 700;
    line-height: 2.17;
    color: #000;
  }

  .arrow-icon {
    width: 21px;
    height: 21px;
    object-fit: contain;
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}
</style>
