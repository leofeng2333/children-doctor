<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goToCapture = () => {
  // TODO: 跳转到拍摄页面
  console.log('开始拍摄')
}
</script>

<template>
  <div class="capture-intro-page">
    <!-- 页面内容 -->
    <div class="content">
      <!-- 标题 -->
      <h1 class="title">
        太棒了！<br />现在我们来拍摄照片
      </h1>

      <!-- 说明文字 -->
      <p class="description">
        根据屏幕提示，请拍摄不同角度的照片。
      </p>

      <!-- 示例图片区域 -->
      <div class="sample-images">
        <!-- 正视图 -->
        <div class="sample-item front-view">
          <div class="corner-frame">
            <div class="corner top-left"></div>
            <div class="corner top-right"></div>
            <div class="corner bottom-left"></div>
            <div class="corner bottom-right"></div>
            <img src="@/assets/images/front-view-sample-3aceb1.png" alt="正视图示例" class="sample-image" />
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
            <img src="@/assets/images/side-view-sample-7b1ece.png" alt="右侧视图示例" class="sample-image" />
          </div>
          <span class="sample-label">右侧视图</span>
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
  height: 100dvh;
  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 30px;
  padding-top: max(42px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
}

.content {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin-top: 24px;
}

.description {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 26px;
  color: #000;
  margin: 16px 0 48px 0;
}

.sample-images {
  display: flex;
  justify-content: space-between;
  margin-top: 36px;
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
  width: 116px;
  height: 107px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 四角边框 */
.corner {
  position: absolute;
  width: 24px;
  height: 24px;
  border: 2px solid #e7e7e7;

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
  width: 100px;
  height: 91px;
  object-fit: cover;
  border-radius: 4px;
}

.sample-label {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 14px;
  font-weight: 400;
  line-height: 1.625;
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
  margin-top: 12px;
}
</style>
