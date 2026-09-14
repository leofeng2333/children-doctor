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

        <!-- 题干 -->
        <p class="question-text">{{ currentQuestion.question }}</p>

        <!-- 题干大插图 —— 只在题目有 image 时渲染 -->
        <img v-if="currentQuestion.image" :src="currentQuestion.image" class="question-image" alt=""
          aria-hidden="true" />

        <!-- 选项区：2x2 圆角卡片 -->
        <div class="options-grid">
          <div v-for="opt in currentQuestion.options" :key="opt.label" class="option-cell">
            <!-- 选项小插图 —— 只在该选项有 image 时渲染,作为该选项的视觉锚点,
                 显示在选项按钮正上方。尺寸小(64x64),保持按钮是主交互元素。 -->
            <img v-if="opt.image" :src="opt.image" class="option-image" alt="" aria-hidden="true" />
            <button class="option-card" :class="optionState(opt.label)" :disabled="!!selectedOption"
              @click="selectOption(opt.label)">
              <!-- 字母角标 —— 放进按钮内,作为左侧"图标位"固定位置,
                   文字因此让出左侧空间保持视觉居中。
                   color 继承按钮(默认黑/答对答错时白)。 -->
              <span class="option-letter">{{ opt.label }}</span>
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

/* 题干大插图 —— 题目文字正下方居中,宽度撑满但不超过 max-width。
   max-width:360px 相比原 600px 缩到约 60%:原图跟选项区(282x2+64=628px)
   几乎同宽,视觉上"图大字小"信息层级混乱;调到 360px 后图明显小于选项区,
   题干主标题、选项区成为视觉主体,题目插图沦为辅助说明,
   符合"先读题、再看图、最后选"的认知流程。 */
.question-image {
  display: block;
  width: 100%;
  max-width: 300px;
  height: auto;
  margin: 28px auto 0;
  border-radius: 24px;
  object-fit: contain;
  background: transparent;
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

/* 选项小插图 —— 显示在选项按钮正上方,作为该选项的视觉锚点。
   64x64:选项卡片 282x129,64x64 占比约 1/2 高,既显眼又不抢按钮主体。
   圆角 16px:和卡片 73.5px 圆角形成"小圆角-大圆角"层级,统一在浅色卡通风格里。
   object-fit:contain:卡通图本身是正方形带留白,用 contain 保证不被裁切。
   margin:24px 0 8px:无 image 时 button 自然贴顶,无视觉异常;
                     有 image 时,image 与 button 间留 8px 间距。
   pointer-events:none:答错抖动时图片不要跟着抖(抖动是 .wrong 上的 transform,
                     只影响按钮);也不阻挡 button 的点击。 */
.option-image {
  display: block;
  width: 64px;
  height: 64px;
  margin: 24px 0 8px;
  border-radius: 16px;
  object-fit: contain;
  background: transparent;
  pointer-events: none;
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
  /* padding-left 让出绝对定位的 .option-letter (56px + 24px 间距 = 80px),
     padding-right 24px 让文字仍在按钮视觉中心稍偏左但不至于贴边。
     padding 不能用 80/24 对称,否则文字在剩余区域里居中后会偏按钮中央右侧;
     24px 右 padding 抵消部分偏移,使文字更接近按钮几何中心。 */
  padding: 0 24px 0 80px;
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

  /* 答对时:角标换成半透明深底,与白字形成强对比(浅白底+白字会糊)。
     rgba(0,0,0,0.18) 跟绿色渐变叠加后呈深墨绿,白字清晰可读。 */
  &.correct .option-letter {
    background: transparent;
  }

  /* 答错时:同 correct 的处理思路,深底+白字。 */
  &.wrong .option-letter {
    background: transparent;
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
  flex: 1;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 700;
  line-height: 52px;
  color: inherit;
  text-align: center;
  /* 极长选项(如"按需点餐，吃不完打包"9 字)在 178px 文字区可能溢出;
     溢出截断 ellipsis,避免把按钮撑变形破坏布局。 */
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: color 0.35s ease;
}

/* 字母角标 —— 放在按钮内左上角的圆形 badge。
   绝对定位脱离文档流,不参与 flex 布局,不影响 .option-text 的居中计算。
   位置:left:16px(贴按钮左内边缘)+ transform translateY(-50%) 垂直居中,
        按钮高 129 - 角标 56 = 73 / 2 ≈ 36.5px 上下间距,与圆角 73.5px 形成呼应。
   尺寸:56x56,圆角 50% 满圆;字号 32px 与 .option-text 一致,保证视觉重量一致。
   背景:rgba(255,255,255,0.45) 半透明白,在黄色卡片上形成"磨砂玻璃"效果,
        在绿色/红色渐变(答对/答错)上仍然透出浅色 base,搭配白字仍有对比。
   color:inherit 让字母颜色跟随按钮 .option-card 的 color:
        默认态按钮 color=#000,字母黑字;答对/答错时按钮 color=#fff,字母白字,
        不需要为每种状态各写一份色值。 */
.option-letter {
  position: absolute;
  top: 50%;
  left: 16px;
  transform: translateY(-50%);
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 700;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
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
