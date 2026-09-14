<script setup lang="ts">
import { saveQuestionAnswers } from '@/utils/service'
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAnalysisStore } from '@/stores'
import { HabitCode } from '@/utils/diagnosisCopy'

const router = useRouter()
const analysisStore = useAnalysisStore()

/**
 * 问题下标 → 坏习惯编码。
 * 每个问卷问题对应一种（或多种）坏习惯，回答"有"（index=0）即触发对应坏习惯。
 *
 * Q0 吮指        → B 龅牙/开𬌗
 * Q1 吮唇        → A 地包天
 * Q2 吐舌/舔牙   → B 龅牙/开𬌗
 * Q3 张口呼吸     → C 上颌前突/下颌后缩
 * Q4 偏侧咀嚼    → D 大小脸/偏颌
 * Q5 啃异物      → B 龅牙/开𬌗
 */
const QUESTION_HABIT_MAP: Record<number, HabitCode> = {
  0: HabitCode.HABIT_PROTRUSION, // 吮指
  1: HabitCode.HABIT_ANTIJOINT, // 吮唇
  2: HabitCode.HABIT_PROTRUSION, // 吐舌/舔牙
  3: HabitCode.HABIT_BREATH, // 张口呼吸
  4: HabitCode.HABIT_ASYMMETRY, // 偏侧咀嚼
  5: HabitCode.HABIT_PROTRUSION, // 啃异物
}

const currentQuestion = ref(1)

const questionTexts = [
  '你是否有吮吸手指的习惯？',
  '你是否会无意识地咬住或吸吮上唇或下唇？',
  '你是否有吐舌头或者舔牙齿的习惯？',
  '你是否有长期张嘴呼吸的习惯，特别是睡着后？',
  '你是否长期只用一边牙齿咀嚼食物？',
  '你是否有啃铅笔、啃筷子等物品或者有咬手指的习惯？',
]

// 选项文案，与 UI 按钮文本保持一致，提交时复用
const answerLabels = ['是，我是这样', '不，这不是我']

// 与 answerLabels 等长的「自包含」文案：脱离题目也能直接看懂回答内容。
// 下标 0 表示肯定的完整陈述，1 表示否定的完整陈述；和题面呼应但不依赖题面。
const filledAnswers: Record<number, [string, string]> = {
  0: ['是，我有吮吸手指的习惯', '不，我没有吮吸手指的习惯'],
  1: ['是，我会无意识地咬住或吸吮嘴唇', '不，我不会无意识地咬住或吸吮嘴唇'],
  2: ['是，我有吐舌头或舔牙齿的习惯', '不，我没有吐舌头或舔牙齿的习惯'],
  3: ['是，我有长期张嘴呼吸的习惯', '不，我没有长期张嘴呼吸的习惯'],
  4: ['是，我只用一边牙齿咀嚼食物', '不，我会用两边牙齿咀嚼食物'],
  5: ['是，我有啃物品或咬手指的习惯', '不，我没有啃物品或咬手指的习惯'],
}

const currentQuestionText = computed(() => questionTexts[currentQuestion.value - 1])
const answers = ref<number[]>([])

const goBack = () => {
  if (currentQuestion.value > 1) {
    currentQuestion.value--
    answers.value.length = currentQuestion.value
  } else {
    router.back()
  }
}

/** 本题选中的选项索引（用于短暂高亮 + 切题前动画） */
const pickedIndex = ref<number | null>(null)
/** 防抖：动画进行中不再响应点击 */
const isAdvancing = ref(false)

interface RipplePos { x: number; y: number }
/** 当前 ripple 触发的选项索引与位置（百分比 0~100） */
const rippleIndex = ref<number | null>(null)
const ripplePos = ref<RipplePos>({ x: 50, y: 50 })

function triggerRipple(event: MouseEvent, index: number) {
  const target = event.currentTarget as HTMLElement | null
  if (!target) return
  const rect = target.getBoundingClientRect()
  ripplePos.value = {
    x: ((event.clientX - rect.left) / rect.width) * 100,
    y: ((event.clientY - rect.top) / rect.height) * 100,
  }
  // 先重置再赋值，确保连点也能重新触发 keyframe
  rippleIndex.value = null
  // 下一帧再赋值
  requestAnimationFrame(() => {
    rippleIndex.value = index
  })
  setTimeout(() => {
    if (rippleIndex.value === index) rippleIndex.value = null
  }, 600)
}

const onPick = async (event: MouseEvent, index: number) => {
  if (isAdvancing.value) return
  isAdvancing.value = true
  pickedIndex.value = index
  triggerRipple(event, index)
  answers.value[currentQuestion.value - 1] = index
  // 短暂停留，让"选中-高亮-切题"看得见
  await new Promise<void>((r) => setTimeout(r, 220))
  pickedIndex.value = null
  isAdvancing.value = false
  nextQuestion()
}

const nextQuestion = () => {
  if (currentQuestion.value < questionTexts.length) {
    currentQuestion.value++
  }
}

const goNext = async () => {
  // 收集"有"该习惯的问题对应的编码，Set 去重后存入 store
  const habitSet = new Set<HabitCode>()
  answers.value.forEach((answerIdx, qIdx) => {
    // answerIdx === 0 表示"有/是"，触发对应坏习惯
    if (answerIdx === 0 && qIdx in QUESTION_HABIT_MAP) {
      habitSet.add(QUESTION_HABIT_MAP[qIdx])
    }
  })
  analysisStore.setBadHabits([...habitSet])

  const tempResponse = await saveQuestionAnswers({
    answers: questionTexts.map((text, idx) => {
      const answerIndex = answers.value[idx] ?? -1
      // 优先用“自包含”陈述句；缺数据时回落到 UI 上的选项文案，保证至少有值。
      const answerText =
        answerIndex >= 0
          ? filledAnswers[idx]?.[answerIndex] ?? answerLabels[answerIndex]
          : ''
      return {
        questionNo: String(idx + 1),
        answer: answerText,
      }
    }),
  })
  console.log('tempResponse', tempResponse)
  router.push({ name: 'quiz-intro' })
}
</script>

<template>
  <div class="question-page">
    <div class="question-content">
      <!-- 返回按钮 -->
      <div class="back-button">
        <img src="@/assets/images/common-left.png" alt="返回" />
      </div>

      <!-- 问询标题 -->
      <div class="header">
        <h1 class="title">为了更好地完成诊断，<br />请认真回答以下问题哦！</h1>
        <p class="progress">( {{ currentQuestion }} / {{ questionTexts.length }} )</p>
      </div>

      <!-- 问题内容 -->
      <div class="content">
        <p class="question-text">{{ currentQuestionText }}</p>

        <!-- 选项按钮 -->
        <div class="options">
          <button
            class="option-button"
            :class="{
              selected: pickedIndex === 0,
              'ripple-active': rippleIndex === 0,
            }"
            @click="onPick($event, 0)"
          >
            <span
              class="ripple"
              :style="{ '--rx': ripplePos.x + '%', '--ry': ripplePos.y + '%' }"
              aria-hidden="true"
            ></span>
            <span class="option-text">有</span>
          </button>
          <button
            class="option-button"
            :class="{
              selected: pickedIndex === 1,
              'ripple-active': rippleIndex === 1,
            }"
            @click="onPick($event, 1)"
          >
            <span
              class="ripple"
              :style="{ '--rx': ripplePos.x + '%', '--ry': ripplePos.y + '%' }"
              aria-hidden="true"
            ></span>
            <span class="option-text">没有</span>
          </button>
        </div>
      </div>
    </div>

    <!-- 底部导航 -->
    <div class="bottom-nav">
      <PrimaryButton v-show="currentQuestion === questionTexts.length" text="下一步" @click="goNext" />
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.question-page {
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

.back-button {
  width: 140px;
  height: 150px;
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
  margin-top: 24px;
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

.progress {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 36px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
  margin: 0;
  margin-top: 4px;
}

.content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  margin-top: 40px;
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
  margin: 0;
}

.options {
  display: flex;
  margin-top: 75px;
}

.option-button {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: none;
  background: #FFE361;
  border-radius: 73.5px;
  cursor: pointer;
  transition:
    background 0.25s cubic-bezier(0.4, 0, 0.2, 1),
    transform 0.2s cubic-bezier(0.4, 0, 0.2, 1),
    box-shadow 0.25s cubic-bezier(0.4, 0, 0.2, 1),
    color 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  width: 282px;
  height: 130px;

  &.selected {
    background: linear-gradient(135deg, #34d399 0%, #22c55e 100%);
    transform: scale(1.04);
    box-shadow:
      0 8px 24px rgba(34, 197, 94, 0.35),
      inset 0 1px 0 rgba(255, 255, 255, 0.25);
    color: #fff;

    .option-text {
      color: #fff;
    }
  }

  &:active:not(.selected) {
    transform: scale(0.96);
  }

  &:first-child {
    margin-right: 48px;
  }
}

.ripple {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: radial-gradient(
    circle at var(--rx, 50%) var(--ry, 50%),
    rgba(255, 255, 255, 0.55) 0%,
    rgba(255, 255, 255, 0) 60%
  );
  opacity: 0;
  transform: scale(0);
}

.option-button.ripple-active .ripple {
  animation: option-ripple 0.55s ease-out;
}

@keyframes option-ripple {
  0% {
    opacity: 1;
    transform: scale(0);
  }
  100% {
    opacity: 0;
    transform: scale(1.4);
  }
}

.option-icon {
  width: 35px;
  height: 35px;
  object-fit: cover;
  margin-top: 8px;
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
  margin-top: 2px;
}

.bottom-nav {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  margin-top: 20px;
}
</style>
