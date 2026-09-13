<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import LogoText from '@/components/LogoText.vue'
import PrimaryButton from '@/components/PrimaryButton.vue'
import { useAnalysisStore } from '@/stores'
import { quizQuestions } from '@/data/quizQuestions'

const router = useRouter()

const analysisStore = useAnalysisStore()
const { result: analysisResult } = storeToRefs(analysisStore)
// 分析接口已返回 (与 DetailAnalysisView.analysisCompleted 同义)
const hasAnalysisResult = computed(() => !!analysisResult.value)

// 随机抽题策略: 维护剩余题目的下标池, 每次抽一个后从池中移除
const remainingIndices = ref<number[]>(
  quizQuestions.map((_, i) => i),
)

const currentIndex = ref<number>(-1)
// 抽下一题 (从剩余池中随机)
const pickNext = (): number => {
  const pool = remainingIndices.value
  if (pool.length === 0) return -1
  const k = Math.floor(Math.random() * pool.length)
  const picked = pool.splice(k, 1)[0] as number
  return picked
}
// 初始化第一题
currentIndex.value = pickNext()

const currentQuestion = computed(() => {
  if (currentIndex.value < 0) return null
  return quizQuestions[currentIndex.value]
})

// 全部答完: 当前停在最后一道题, 且池里已无题
const isFinished = computed(
  () => currentIndex.value >= 0 && remainingIndices.value.length === 0,
)

const selectedOption = ref<string | null>(null)

const selectOption = (label: string) => {
  // 已选过/已揭晓的不再响应
  if (selectedOption.value) return
  selectedOption.value = label
}

const optionState = (label: string) => {
  if (!selectedOption.value) return ''
  const isMe = label === selectedOption.value
  const isCorrect = label === currentQuestion.value?.answer
  if (isCorrect) return 'correct'
  if (isMe) return 'wrong'
  return ''
}

/** 已揭晓 + 该项是正确答案: 用于飘出爱心 */
const showHeart = (label: string) => {
  return !!selectedOption.value && label === currentQuestion.value?.answer
}

const goNext = () => {
  // 分析接口已返回 -> 直接跳转到分析结果页面
  if (hasAnalysisResult.value) {
    router.push({ name: 'detail-analysis' })
    return
  }
  // 否则正常抽下一题
  if (remainingIndices.value.length === 0) return
  selectedOption.value = null
  currentIndex.value = pickNext()
}

const goResult = () => {
  router.push({ name: 'detail-analysis' })
}
</script>

<template>
  <div class="quiz-page">
    <div class="quiz-content">
      <!-- 答题态 -->
      <div v-if="currentQuestion">
        <!-- 标题 -->
        <h1 class="title">趣味问答</h1>

        <!-- 描述 -->
        <p class="desc">
          预测结果正在生成中，<br />等待期间来玩一组趣味问答吧！
        </p>

        <!-- 题目 -->
        <p class="question-text">{{ currentQuestion.question }}</p>

        <!-- 选项区：2x2 圆角卡片 -->
        <div class="options-grid">
          <div v-for="opt in currentQuestion.options" :key="opt.label" class="option-cell">
            <span class="option-label">{{ opt.label }}</span>
            <button class="option-card" :class="optionState(opt.label)" :disabled="!!selectedOption"
              @click="selectOption(opt.label)">
              <span class="option-text">{{ opt.text }}</span>
            </button>
            <span v-if="showHeart(opt.label)" class="float-heart" aria-hidden="true">❤</span>
          </div>
        </div>
        <div class="next-btn-container">
          <button v-if="selectedOption && !isFinished" class="next-btn" @click="goNext">
            <span class="next-text">下一个</span>
            <img src="@/assets/go-right.svg" class="next-icon" alt="" aria-hidden="true" />
          </button>
        </div>
      </div>
    </div>

    <!-- 底部区：logo -->
    <div class="bottom-section">
      <PrimaryButton v-if="isFinished" class="result-btn" text="查看分析结果" @click="goResult" />
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
  font-weight: 700;
  line-height: 52px;
  color: #000;
  margin: 42px 0 0 0;
}

.desc {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 45px;
  color: #000;
  margin: 24px 0 0 0;
}

.options-grid {
  display: grid;
  grid-template-columns: 282px 282px;
  column-gap: 64px;
  row-gap: 48px;
  margin-top: 75px;
  justify-content: center;
}

.next-btn-container {
  display: flex;
  justify-content: flex-end;
  margin-top: 80px;
  padding-right: 48px;
}

.option-cell {
  position: relative;
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
  position: relative;
  background: #FFE361;
  border: 2px solid transparent;
  border-radius: 73.5px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.35s cubic-bezier(0.4, 0, 0.2, 1),
    color 0.35s cubic-bezier(0.4, 0, 0.2, 1),
    border-color 0.35s cubic-bezier(0.4, 0, 0.2, 1),
    transform 0.35s cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 0.35s cubic-bezier(0.4, 0, 0.2, 1);
  padding: 0;
  width: 282px;
  height: 129px;
  overflow: hidden;

  &:active {
    transform: scale(0.98);
  }

  &:disabled {
    cursor: default;
  }

  &.correct {
    background: linear-gradient(135deg, #34d399 0%, #22c55e 100%);
    border-color: rgba(255, 255, 255, 0.4);
    color: #fff;
    box-shadow:
      0 8px 24px rgba(34, 197, 94, 0.35),
      inset 0 1px 0 rgba(255, 255, 255, 0.25);
    transform: scale(1.02);
  }

  &.wrong {
    background: linear-gradient(135deg, #fb7185 0%, #ef4444 100%);
    border-color: rgba(255, 255, 255, 0.4);
    color: #fff;
    box-shadow:
      0 8px 24px rgba(239, 68, 68, 0.35),
      inset 0 1px 0 rgba(255, 255, 255, 0.25);
    transform: scale(1.02);
    animation: option-shake 0.55s cubic-bezier(0.36, 0.07, 0.19, 0.97) both;
  }
}

// 答错时水平抖动
@keyframes option-shake {

  0%,
  100% {
    transform: scale(1.02) translateX(0);
  }

  15% {
    transform: scale(1.02) translateX(-10px);
  }

  30% {
    transform: scale(1.02) translateX(10px);
  }

  45% {
    transform: scale(1.02) translateX(-7px);
  }

  60% {
    transform: scale(1.02) translateX(7px);
  }

  75% {
    transform: scale(1.02) translateX(-4px);
  }

  90% {
    transform: scale(1.02) translateX(4px);
  }
}

// 答对时从正确答案按钮飘出爱心
.float-heart {
  position: absolute;
  top: 50%;
  left: 50%;
  font-size: 38px;
  line-height: 1;
  color: #fff;
  pointer-events: none;
  z-index: 2;
  text-shadow:
    0 4px 12px rgba(255, 100, 130, 0.45),
    0 0 0 1px rgba(255, 255, 255, 0.4);
  animation: float-heart 0.95s cubic-bezier(0.22, 1, 0.36, 1) forwards;
}

@keyframes float-heart {
  0% {
    opacity: 0;
    transform: translate(-50%, 0) scale(0.6);
  }

  20% {
    opacity: 1;
    transform: translate(-50%, -18px) scale(1.3);
  }

  100% {
    opacity: 0;
    transform: translate(-50%, -130px) scale(1);
  }
}

// 选中后右上角的对错标记 (零依赖, 纯 CSS)
.option-card.correct::after,
.option-card.wrong::after {
  content: '';
  position: absolute;
  top: 12px;
  right: 18px;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  font-weight: 700;
  line-height: 1;
  color: #fff;
  background: rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(4px);
  animation: popIn 0.35s cubic-bezier(0.34, 1.56, 0.64, 1) both;
}

.option-card.correct::after {
  content: '\2713'; // ✓
}

.option-card.wrong::after {
  content: '\2717'; // ✗
}

@keyframes popIn {
  0% {
    opacity: 0;
    transform: scale(0.3) rotate(-30deg);
  }

  100% {
    opacity: 1;
    transform: scale(1) rotate(0deg);
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
  color: inherit;
  transition: color 0.35s ease;
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

// PrimaryButton 默认 width: 90%, 这里让"查看分析结果"在底部居中显示
.result-btn {}

.next-btn {
  background: #d9d9d9;
  border: none;
  border-radius: 73.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  gap: 12px;
  padding: 0 16px 0 28px;
  height: 58px;
  transition: all 0.2s ease;

  &:active {
    transform: scale(0.98);
  }
}

.next-text {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 24px;
  font-weight: 700;
  line-height: 52px;
  color: #000;
  white-space: nowrap;
}

.next-icon {
  width: 42px;
  height: 42px;
  display: block;
  pointer-events: none;
}
</style>
