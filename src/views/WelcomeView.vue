<script setup lang="ts">
import { useRouter } from 'vue-router'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '../components/LogoText.vue'
import { createSession } from '@/utils/service'
import { onMounted } from 'vue'

const router = useRouter()

const goToForm = () => {
  router.push('/diagnosis')
}

const goToPrintTest = () => {
  router.push('/print-test')
}

onMounted(async () => {
  const sessionInfo = await createSession()
  console.log('sessionInfo', sessionInfo)
})
</script>

<template>
  <div class="welcome-container">
    <!-- 顶部装饰图形：靠着 bottom-section 顶部边界左右分布 -->
    <div class="top-section">
      <div class="shape shape-square" aria-hidden="true"></div>
      <div class="shape shape-semicircle" aria-hidden="true"></div>
      <div class="shape shape-triangle" aria-hidden="true">
        <img src="@/assets/images/triangle-icon.png" alt="triangle" />
      </div>
    </div>

    <!-- 底部黄色区域 -->
    <div class="bottom-section">
      <div class="bottom-section-content">
        <!-- 小图标 -->
        <div class="small-icon">
          <img src="@/assets/images/common-left.png" alt="口腔科图标" />
        </div>

        <!-- 主标题 -->
        <h1 class="main-title">魔法笑容小侦探<br />拍照寻宝大冒险</h1>

        <!-- 副标题 -->
        <p class="sub-title">AI辅助儿童颌面发育诊断</p>
      </div>
      <!-- 按钮 -->
      <div class="bottom-section-buttons">
        <PrimaryButton text="走进诊所" color="#fff" @click="goToForm" />
        <PrimaryButton class="print-test-btn" text="打印测试" color="#fff" @click="goToPrintTest" />

        <div class="support-text">本AI诊断系统由杭州儿童口腔医院专业支持</div>
        <!-- Logo -->
        <LogoText class="logo" />
      </div>
    </div>
    <!-- 右上角开发者入口已统一上移到全局 DevToolsLauncher（App.vue），本页面不再维护。 -->
  </div>
</template>

<style scoped lang="scss">
.welcome-container {
  height: 100vh;
  position: relative;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #fdfdfd;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 顶部区域 */
.top-section {
  height: 360px;
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  padding: 0 60px;
  padding-top: max(20px, env(safe-area-inset-top));
  position: relative;
  z-index: 12; // 比 bottom-section 的 11 高, 让图形压在黄色背景上方
}

// 三个纯色图形：水平靠 bottom-section 顶部边界分布, 高度统一由 .shape 控制.
// 视觉上图文相符:
//   - 矩形: 倾斜 ~10°, 圆角小, 整体抬高, 上半部在 top-section、下半部压入 bottom-section
//   - 半圆: 平边朝下, 圆顶朝上, 整个图形底边对齐 top-section 底边
//   - 三角形: 尖角朝上, 三个角圆角, 底边对齐 top-section 底边
.shape {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
}

// 1. 倾斜的橙红色正方形（左上）
.shape-square {
  width: 137px;
  height: 137px;
  border-radius: 6px;
  background: #F4A08D;
  transform: rotate(-30deg);
  // margin-bottom: -36px; // 探入 bottom-section
  margin-bottom: 25px;
  align-self: flex-end;
}

// 2. 半圆：平边朝下, 圆顶朝上 (中间)
.shape-semicircle {
  width: 234px;
  height: 119px; // 高度减半, 加上 border-radius 实现半圆
  background: #ff5a3c;
  border-radius: 117px 117px 0 0;
  transform: rotate(-24deg);
  margin-bottom: 45px;
}

// 3. 三角形：尖角朝上, 三个角圆滑 (右上)
//    CSS clip-path: polygon 没有圆角选项, 用 inline SVG mask 画圆角三角形.
//    路径: 顶(50,10) → 右下(90,90) → 左下(10,90) → 闭合, 每段 L 之间用 Q 圆化.
//    每条边的近顶点 8px 段切走, 用 Q 二次贝塞尔圆化.
.shape-triangle {
  img {
    width: 230px;
  }

  margin-bottom: 75px;
  margin-left: -48px;
}

/* 底部区域 */
.bottom-section {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  background: linear-gradient(180deg, #ffe361 0%, #ffd93d 100%);
  border-radius: 0 500px 0 0;
  padding: 85px;
  padding-top: 120px;
  padding-bottom: calc(30px + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: space-between;
  position: relative;
  z-index: 11;

  .bottom-section-buttons {
    width: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;

    .support-text {
      font-size: 20px;
      line-height: 1;
      text-align: center;
      margin-top: 32px;
    }
  }
}

.small-icon {
  width: 140px;
  height: 150px;
  margin-bottom: 32px;
}

.small-icon img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.main-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 96px;
  font-weight: 700;
  line-height: 120px;
  color: #000;
  margin: 0 0 16px 0;
}

.sub-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 1.8;
  color: #000;
  margin: 0 0 40px 0;
}

.logo {
  margin-top: 20px;
}

// "打印测试" 按钮 - 次级视觉权重, 不抢主 CTA 颜色
.print-test-btn {
  margin-top: 24px !important;
  background: #fff !important;
  color: #ff9900 !important;
  border: 2px solid #ff9900 !important;
  box-shadow: none !important;

  &:active {
    background: #fff7e6 !important;
  }
}
</style>
