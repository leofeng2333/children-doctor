<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goToCapture = () => {
  // TODO: 跳转到拍摄页面
  console.log('开始拍摄')
  router.push('/capture')
}
</script>

<template>
  <div class="capture-intro-page">
    <!-- 页面内容 -->
    <div class="content">
      <!-- 顶部：吉祥物 + 标题区 -->
      <div class="hero">
        <img src="@/assets/images/common-left.png" alt="" class="mascot" aria-hidden="true" />
        <div class="hero-text">
          <h1 class="title">欢迎来到拍照环节</h1>
          <p class="description">请根据屏幕提示，<br />拍摄露齿笑与闭嘴巴两组照片哦！</p>
        </div>
      </div>

      <!-- 示例图片区域 -->
      <div class="sample-images">
        <!-- 正视图 -->
        <div class="sample-item front-view">
          <div class="corner-frame">
            <div class="corner top-left"></div>
            <div class="corner top-right"></div>
            <div class="corner bottom-left"></div>
            <div class="corner bottom-right"></div>
            <img src="@/assets/images/capture-sample-right.png" alt="正视图示例" class="sample-image" />
          </div>
          <span class="sample-label">正视图</span>
        </div>

        <!-- 右侧视图 -->
        <div class="sample-item side-view">
          <div class="corner-frame">
            <div class="corner top-left"></div>
            <div class="corner top-right"></div>
            <div class="corner bottom-left"></div>
            <div class="corner bottom-right"></div>
            <img src="@/assets/images/capture-sample-left.png" alt="右侧视图示例" class="sample-image" />
          </div>
          <span class="sample-label">侧视图</span>
        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <div class="bottom-section">
      <PrimaryButton text="开始拍摄" @click="goToCapture" />
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.capture-intro-page {
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
}

.content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  display: flex;
  flex-direction: column;
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

.hero {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 32px;
  margin-top: 24px;
}

.mascot {
  flex: 0 0 auto;
  width: 140px;
  height: 150px;
  object-fit: contain;
}

.hero-text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.title-underline {
  width: 320px;
  max-width: 100%;
  height: 24px;
  margin-top: 4px;
}

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
}

.sample-images {
  display: flex;
  justify-content: space-between;
  margin-top: 150px;
  padding: 0 50px;
}

.sample-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.front-view {
  margin-left: 20px;
}

.side-view {
  margin-right: 20px;
}

/* 四角边框容器 */
.corner-frame {
  position: relative;
  width: 240px;
  height: 220px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 四角边框 */
.corner {
  position: absolute;
  width: 56px;
  height: 56px;
  border: 4px solid #FF9900;

  &.top-left {
    top: 0;
    left: 0;
    border-right: none;
    border-bottom: none;
  }

  &.top-right {
    top: 0;
    right: 0;
    border-left: none;
    border-bottom: none;
  }

  &.bottom-left {
    bottom: 0;
    left: 0;
    border-right: none;
    border-top: none;
  }

  &.bottom-right {
    bottom: 0;
    right: 0;
    border-left: none;
    border-top: none;
  }
}

.sample-image {
  width: 200px;
  height: 180px;
  object-fit: cover;
  border-radius: 4px;
}

.sample-label {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin-top: 20px;
}

.front-view .sample-label {
  text-align: center;
}

.side-view .sample-label {
  text-align: right;
}

.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo {
  margin-top: 20px;
}
</style>
