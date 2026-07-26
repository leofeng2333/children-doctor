<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '../components/LogoText.vue'
import IconButton from '../components/IconButton.vue'
import PasswordDialog from '../components/PasswordDialog.vue'
import { createSession } from '@/utils/service'
import { useFlowStore } from '@/stores/flow'
import { onMounted } from 'vue'

const router = useRouter()
const flowStore = useFlowStore()

const showPasswordDialog = ref(false)

const goToForm = () => {
  router.push('/form')
}

const goToPrintTest = () => {
  router.push('/print-test')
}

// 密码验证通过：切换长/短流程（不再跳打印测试页，避免覆盖原调试入口）
const handlePasswordSuccess = () => {
  flowStore.toggle()
  console.log(`[flow] 已切换为${flowStore.mode === 'long' ? '长' : '短'}流程`)
}

const handleAdminButtonClick = () => {
  showPasswordDialog.value = true
}

onMounted(async () => {
  const sessionInfo = await createSession()
  console.log('sessionInfo', sessionInfo)
})
</script>

<template>
  <div class="welcome-container">
    <!-- 右上角空白按钮 + 流程模式指示点 -->
    <div class="admin-area">
      <IconButton class="admin-button" @dblclick="handleAdminButtonClick" />
      <span
        class="flow-indicator"
        :class="{ 'is-short': flowStore.isShort }"
        :title="flowStore.isShort ? '当前：短流程' : '当前：长流程'"
      ></span>
    </div>

    <!-- 顶部口腔科图片 -->
    <div class="top-section">
      <div class="mouth-image">
        <img src="@/assets/images/tooth2.png" alt="口腔科" />
      </div>
    </div>

    <!-- 底部黄色区域 -->
    <div class="bottom-section">
      <div class="bottom-section-content">
        <!-- 小图标 -->
        <div class="small-icon">
          <img src="@/assets/images/tooth1.png" alt="口腔科图标" />
        </div>

        <!-- 主标题 -->
        <h1 class="main-title">儿童颜面发育<br />AI预诊断</h1>

        <!-- 副标题 -->
        <p class="sub-title">识别诊断 × AI预测 × 专家建议</p>
      </div>
      <!-- 按钮 -->
      <div class="bottom-section-buttons">
        <PrimaryButton text="走进诊所" color="#fff" @click="goToForm" />
        <PrimaryButton
          class="print-test-btn"
          text="打印测试"
          color="#fff"
          @click="goToPrintTest"
        />

        <div class="support-text">本AI诊断系统由杭州儿童口腔医院专业支持</div>
        <!-- Logo -->
        <LogoText class="logo" />
      </div>
    </div>

    <!-- 密码验证弹窗 -->
    <PasswordDialog
      :visible="showPasswordDialog"
      @close="showPasswordDialog = false"
      @success="handlePasswordSuccess"
    />
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
  height: 300px;
  display: flex;
  justify-content: flex-end;
  padding: 20px;
  padding-top: max(20px, env(safe-area-inset-top));
  position: relative;
}

.mouth-image {
  width: 268px;
  height: 268px;
  border-radius: 50%;
  overflow: hidden;

  position: absolute;
  bottom: -160px;
  right: 140px;
  z-index: 10;
}

/* 右上角管理员按钮 */
.admin-area {
  position: absolute;
  top: max(20px, env(safe-area-inset-top));
  right: max(20px, env(safe-area-inset-right));
  z-index: 20;
}

.admin-button {
  // 由父级 .admin-area 控制定位
  position: static;
}

// 流程模式指示点：长流程=绿色圆点，短流程=橙色圆点
.flow-indicator {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #2ecc71;
  box-shadow: 0 0 0 2px #ffffff;
}

.flow-indicator.is-short {
  background: #ff9900;
}

.mouth-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
  width: 88px;
  height: 88px;
  overflow: hidden;
  margin-bottom: 32px;
}

.small-icon img {
  width: 100%;
  height: 100%;
  object-fit: cover;
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
