<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goToForm = () => {
  router.push('/form')
}
</script>

<template>
  <div class="diagnosis-container">
    <!-- 主标题 -->
    <h1 class="main-title">
      Hi，<br />
      我是你的AI口腔医生！
    </h1>

    <!-- 说明文字 -->
    <p class="description">
      该设备通过简单的问答和对使用者面容的专业医学视觉分析，帮助使用者提前发现颌骨问题并预测未来发展趋势。
    </p>

    <!-- 步骤区域 -->
    <div class="steps-container">
      <!-- 第一步 -->
      <div class="step step-left">
        <div class="step-circle"></div>
        <div class="step-text">
          第一步，<br />
          由一些简单的问题开启问诊
        </div>
      </div>

      <!-- 连接线1 -->
      <div class="connector connector-right"></div>

      <!-- 第二步 -->
      <div class="step step-right">
        <div class="step-text step-text-right">
          快好了！<br />
          看镜头，我们来观测一下面容
        </div>
        <div class="step-circle"></div>
      </div>

      <!-- 连接线2 -->
      <div class="connector connector-left"></div>

      <!-- 第三步 -->
      <div class="step step-left step-last">
        <div class="step-circle"></div>
        <div class="step-text">
          结束！<br />
          领取你的面容预测报告
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <div class="bottom-section">
      <button class="start-button" @click="goToForm">
        开始问诊
      </button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.diagnosis-container {
  min-height: 100vh;
  min-height: 100dvh;
  background: #fff;
  padding: 40px 24px;
  padding-top: max(40px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
}

/* 主标题 */
.main-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 42px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin: 0 0 24px 0;
  padding-left: 8px;
}

/* 说明文字 */
.description {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 18px;
  font-weight: 400;
  line-height: 1.625;
  color: #000;
  margin: 0 0 48px 0;
  padding: 0 8px;
}

/* 步骤容器 */
.steps-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0;
  position: relative;
}

/* 步骤项 */
.step {
  display: flex;
  align-items: center;
  gap: 16px;
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
  margin-top: -8px;
}

/* 圆形图标 */
.step-circle {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #D9D9D9;
  flex-shrink: 0;
}

/* 步骤文字 */
.step-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.4;
  color: #000;
  max-width: 180px;
}

.step-text-right {
  text-align: right;
}

/* 连接线 */
.connector {
  width: 100px;
  height: 60px;
  position: relative;
}

.connector-right {
  align-self: flex-end;
  margin-right: 24px;
  background: linear-gradient(to bottom right, transparent 50%, #D9D9D9 50%);
  background-size: 100% 50%;
  background-position: top left;
  background-repeat: no-repeat;
}

.connector-left {
  align-self: flex-start;
  margin-left: 24px;
  margin-top: -8px;
  background: linear-gradient(to bottom left, transparent 50%, #D9D9D9 50%);
  background-size: 100% 50%;
  background-position: top right;
  background-repeat: no-repeat;
}

/* 底部按钮 */
.bottom-section {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 32px;
}

.start-button {
  width: 100%;
  max-width: 320px;
  background: #FF9900;
  color: #fff;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 18px;
  font-weight: 700;
  border: none;
  border-radius: 50px;
  padding: 16px 48px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  transition: all 0.3s ease;
}

.start-button:active {
  transform: scale(0.98);
  box-shadow: 0 4px 12px rgba(255, 153, 0, 0.3);
}
</style>