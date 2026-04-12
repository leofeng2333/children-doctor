<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const goToHome = () => {
  router.push('/')
}
</script>

<template>
  <div class="welcome-container">
    <!-- 顶部口腔科图片 -->
    <div class="top-section">
      <div class="mouth-image">
        <img src="https://figma-alpha-api.s3.us-west-2.amazonaws.com/images/0cb7dd115bdb938b437f3dbb3c68668b9f380c63"
          alt="口腔科" />
      </div>
    </div>

    <!-- 底部黄色区域 -->
    <div class="bottom-section">
      <div class="bottom-section-content">
        <!-- 小图标 -->
        <div class="small-icon">
          <img src="https://figma-alpha-api.s3.us-west-2.amazonaws.com/images/0cb7dd115bdb938b437f3dbb3c68668b9f380c63"
            alt="口腔科图标" />
        </div>

        <!-- 主标题 -->
        <h1 class="main-title">
          儿童颜面发育<br />AI预诊断
        </h1>

        <!-- 副标题 -->
        <p class="sub-title">识别诊断 × AI预测 × 专家建议</p>

      </div>
      <!-- 按钮 -->
      <button class="enter-btn" @click="goToHome">
        走进诊所
      </button>

      <!-- Logo -->
      <div class="logo-text">logo</div>
    </div>
  </div>
</template>

<style scoped>
.welcome-container {
  min-height: 100vh;
  background: #FDFDFD;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部区域 */
.top-section {
  position: relative;
  height: 20vh;
  display: flex;
  justify-content: flex-end;
  padding: 20px;
}

.mouth-image {
  width: 100px;
  height: 100px;
  border-radius: 50%;
  overflow: hidden;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}

.mouth-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 底部区域 */
.bottom-section {
  flex: 1;
  background: linear-gradient(180deg, #FFE361 0%, #FFD93D 100%);
  border-radius: 0 240px 0 0;
  padding: 40px 30px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
}

.small-icon {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  overflow: hidden;
  margin-bottom: 20px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.small-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.main-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 48px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin: 0 0 16px 0;
}

.sub-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 18px;
  font-weight: 400;
  line-height: 2.8;
  color: #000;
  margin: 0 0 40px 0;
}

.enter-btn {
  width: 90%;
  margin: 0 auto;
  background: #FF9900;
  color: white;
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 20px;
  font-weight: 700;
  border: none;
  border-radius: 50px;
  padding: 18px 60px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  transition: all 0.3s ease;
  margin-bottom: 20px;
}

.enter-btn:active {
  transform: scale(0.98);
  box-shadow: 0 4px 12px rgba(255, 153, 0, 0.3);
}

.logo-text {
  position: absolute;
  bottom: 30px;
  left: 50%;
  transform: translateX(-50%);
  font-family: 'Inter', sans-serif;
  font-size: 14px;
  color: #BCBCBC;
}

/* 移动端适配 */
@media (max-width: 480px) {
  .top-section {
    height: 18vh;
  }

  .mouth-image {
    width: 160px;
    height: 160px;
  }

  .main-title {
    font-size: 36px;
  }

  .sub-title {
    font-size: 16px;
  }

  .enter-btn {
    padding: 16px 48px;
    font-size: 18px;
  }
}

/* 安全区域适配 */
@supports (padding-bottom: env(safe-area-inset-bottom)) {
  .logo-text {
    bottom: calc(30px + env(safe-area-inset-bottom));
  }
}
</style>
