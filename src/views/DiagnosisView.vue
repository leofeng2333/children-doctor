<script setup lang="ts">
import LogoText from '@/components/LogoText.vue'
import PrimaryButton from '@/components/PrimaryButton.vue'
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const stepsContainerRef = ref<HTMLElement>()

const goToForm = () => {
  router.push('/question')
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
    let x1, y1, x2, y2: number;
    if (i === 1) {
      x1 = prev.right - containerRect.left + 10
      y1 = prev.top + prev.height / 2 - containerRect.top + 15
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
      <div class="welcome-text">
        Hi，<br />我是你的AI口腔医生！
      </div>

      <!-- 说明文字 -->
      <p class="description">
        该设备通过简单的问答和对使用者面容的专业医学视觉分析，帮助使用者提前发现颌骨问题并预测未来发展趋势。
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
          <div class="step-circle"></div>
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
          <div class="step-circle"></div>
        </div>

        <!-- 第三步 -->
        <div class="step step-left step-last">
          <div class="step-circle"></div>
          <div class="step-text">
            <h2 class="step-text-title">结束！</h2>
            <p class="step-text-content">领取你的面容预测报告</p>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <div class="bottom-section">
      <PrimaryButton text="开始问诊" class="start-button" @click="goToForm">
      </PrimaryButton>
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.diagnosis-container {
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

/* 欢迎语 */
.welcome-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin-top: 24px;
}

/* 说明文字 */
.description {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  color: #000;
  margin: 16px 0 48px 0;
}

/* 步骤容器 */
.steps-container {
  flex: 1;
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
  flex: 1;
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
  stroke: #D9D9D9;
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
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #D9D9D9;
  flex-shrink: 0;
  align-self: center;
}

/* 步骤文字 */
.step-text {
  height: 100%;
  flex: 1;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  color: #000;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  margin-left: 8px;
  zoom: 0.8;


  .step-text-title {
    font-size: 12px;
    font-weight: 700;
  }

  .step-text-content {
    font-size: 12px;
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
  margin-top: 12px;
}
</style>
