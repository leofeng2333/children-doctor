<script setup lang="ts">
import LogoText from '@/components/LogoText.vue'
import PrimaryButton from '@/components/PrimaryButton.vue'
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const stepsContainerRef = ref<HTMLElement>()

const goNext = () => {
  router.push('/capture-intro')
}

const drawConnectors = () => {
  if (!stepsContainerRef.value) return
  const svg = stepsContainerRef.value.querySelector('svg.connectors-svg') as SVGSVGElement | null
  if (!svg) return

  const circles = Array.from(stepsContainerRef.value.querySelectorAll<HTMLElement>('.step-circle'))
  const containerRect = stepsContainerRef.value.getBoundingClientRect()

  circles.forEach((circle, i) => {
    const prevCircle = circles[i - 1]
    if (!prevCircle) return

    const prev = prevCircle.getBoundingClientRect()
    const curr = circle.getBoundingClientRect()
    let x1, y1, x2, y2: number
    if (i === 1) {
      x1 = prev.right - containerRect.left + 15
      y1 = prev.top + prev.height / 2 - containerRect.top + 20
      x2 = curr.left - containerRect.left - 10
      y2 = curr.top - containerRect.top + 24
    } else {
      x1 = prev.left - containerRect.left + 10
      y1 = prev.bottom - containerRect.top + 10
      x2 = curr.right - containerRect.left + 10
      y2 = curr.top - containerRect.top + 24
    }

    const cpX = (x1 + x2) / 2 + 10
    let cpY: number
    if (i === 2) {
      cpY = Math.max(y1, y2) + Math.abs(y2 - y1) * 0.1
    } else {
      cpY = Math.min(y1, y2) - Math.abs(y2 - y1) * 0.1
    }
    const path = `M ${x1} ${y1} Q ${cpX} ${cpY} ${x2} ${y2}`
    const pathEl = svg.querySelector<SVGPathElement>(`#connector-${i}`)
    if (pathEl) {
      pathEl.setAttribute('d', path)
      pathEl.setAttribute('marker-end', 'url(#arrowhead)')
    }
  })
}

let resizeObserver: ResizeObserver | null = null

onMounted(() => {
  drawConnectors()

  resizeObserver = new ResizeObserver(() => drawConnectors())
  if (stepsContainerRef.value) {
    resizeObserver.observe(stepsContainerRef.value)
  }
})

onUnmounted(() => {
  resizeObserver?.disconnect()
})
</script>

<template>
  <div class="diagnosis-container">
    <div class="page-top-container">
      <!-- 欢迎语 -->
      <div class="welcome-container">
        <img src="@/assets/images/common-left.png" alt="" class="mascot" aria-hidden="true" />
        <div class="welcome-text">Hi，我是<br />你的AI口腔医生！</div>
      </div>

      <!-- 说明文字 -->
      <p class="description">
        亲爱的小朋友们，我将通过为你拍摄照片，结合你的简单问答后，进行颌面发育分析。<br />这样可以帮助爸爸妈妈更好地掌握你的颌面发育情况，以及面部可能的未来发展趋势。
      </p>

      <!-- 步骤区域 -->
      <div class="steps-container" ref="stepsContainerRef">
        <!-- SVG 连接线层 -->
        <svg class="connectors-svg" aria-hidden="true">
          <defs>
            <marker id="arrowhead" markerWidth="3.75" markerHeight="3.75" refX="3.375" refY="1.875" orient="auto">
              <path d="M 0 0 L 3.75 1.875 L 0 3.75 z" fill="#D9D9D9" />
            </marker>
          </defs>
          <path id="connector-1" class="connector-path" />
          <path id="connector-2" class="connector-path" />
        </svg>

        <!-- 第一步 -->
        <div class="step step-left first-step">
          <div class="step-circle">
            <img src="@/assets/images/step1.png" alt="第一步" class="step-circle-img" />
          </div>
          <div class="step-text">
            <h2 class="step-text-title">第一步，</h2>
            <p class="step-text-content">由一些简单的问题开启问诊</p>
          </div>
        </div>

        <!-- 第二步 -->
        <div class="step step-right step-second">
          <div class="step-text step-text-right">
            <h2 class="step-text-title">快好了！</h2>
            <p class="step-text-content">看镜头，我们来观测一下面容</p>
          </div>
          <div class="step-circle">
            <img src="@/assets/images/step2.png" alt="第二步" class="step-circle-img" />
          </div>
        </div>

        <!-- 第三步 -->
        <div class="step step-left step-last">
          <div class="step-circle">
            <img src="@/assets/images/step3.png" alt="第三步" class="step-circle-img" />
          </div>
          <div class="step-text">
            <h2 class="step-text-title">结束！</h2>
            <p class="step-text-content">领取你的面容预测报告</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <div class="bottom-section">
      <PrimaryButton text="开始分析" class="start-button" @click="goNext"> </PrimaryButton>
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.diagnosis-container {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #FAFAFA;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
  justify-content: space-between;
}

.welcome-container {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 32px;

  .mascot {
    width: 140px;
    height: 150px;
    object-fit: contain;
  }
}

/* 欢迎语 */
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

/* 说明文字 */
.description {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 48px 0 48px 0;
}

/* 步骤容器 */
.steps-container {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
  position: relative;
  min-height: 0;
}

/* page-top-container 容器高度 */
.page-top-container {
  display: flex;
  flex-direction: column;
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  min-height: 0;
}

/* SVG 连接线 */
.connectors-svg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  overflow: visible;
  z-index: 0;
}

.connector-path {
  fill: none;
  stroke: #d9d9d9;
  stroke-width: 2;
  stroke-linecap: round;
}

/* 步骤项 */
.step {
  display: flex;
  align-items: center;
  width: 100%;
  position: relative;
  z-index: 1;
}

.step-left {
  justify-content: flex-start;
  padding-left: 8px;
}

.step-right {
  justify-content: flex-end;
  padding-right: 8px;
}

.step-last {
  padding-left: 24px;
  margin-top: 32px;

  .step-text {
    margin-bottom: 28px;
  }
}

/* 圆形图标 */
.step-circle {
  width: 172px;
  height: 172px;
  border-radius: 50%;
  background: #d9d9d9;
  flex-shrink: 0;
  align-self: center;
  // overflow: hidden;
  position: relative;
  padding-bottom: 12px;
}

.step-circle-img {
  width: 90%;
  height: 100%;
  object-fit: scale;
  display: block;
  margin-left: 12px;
}

.first-step {
  .step-circle {
    background-color: #FF9900;
  }
}

.step-second {
  .step-circle {
    background-color: #F2A4A2;

    .step-circle-img {
      transform: scaleX(-1) rotate(20deg);
    }
  }
}

.step-last {
  .step-circle {
    background-color: #F2684E;
  }
}

/* 步骤文字 */
.step-text {
  height: 100%;
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-weight: 400;
  color: #000;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  margin-left: 16px;

  .step-text-title {
    font-size: 24px;
    font-weight: 700;
  }

  .step-text-content {
    font-size: 24px;
    font-weight: 400;
    color: #000;
    line-height: 20px;
    line-height: 1.05;
  }
}

.first-step {
  .step-text {
    justify-content: flex-start;
    margin-top: 12px;
  }
}

.step-second {
  .step-text {
    justify-content: center;
    margin-top: 12px;
    margin-right: 12px;
  }
}

.step-text-right {
  text-align: right;
}

/* 底部按钮 */
.bottom-section {
  display: flex;
  align-items: center;
  flex-direction: column;
}

.logo {
  margin-top: 20px;
}
</style>
