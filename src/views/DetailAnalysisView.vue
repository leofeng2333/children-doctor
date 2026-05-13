<script setup lang="ts">
import LogoText from '@/components/LogoText.vue'
import PrimaryButton from '@/components/PrimaryButton.vue'
import { getAnalysisResult, startAnalysis } from '@/utils/service'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'


const router = useRouter()

const analysisTaskId = useRoute().query.taskId as string;
const analysisResult = ref<any>();

const loading = ref(true);
// const analysisResult = ref<any>({
//   success: false,
// });

const analysisCompleted = computed(() => {
  return !!analysisResult.value;
})

const analysisResultTag = computed(() => {
  return analysisResult.value?.llmAnalysis.result.isHealthy;
})

const handleReturnReport = () => {
  router.back()
}

const swiperIndex = ref(0);

const handleSlideChange = (index: number) => {
  swiperIndex.value = index;
}

onMounted(async () => {

  //   {
  //     "code": 200,
  //     "message": "操作成功",
  //     "data": {
  //         "followTaskId": "follow_100024b8b9e1431f",
  //         "qrcodeUrl": "https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=gQE58TwAAAAAAAAAAS5odHRwOi8vd2VpeGluLnFxLmNvbS9xLzAyZjZwSm9kaWZlWHMxMDAwMDAwN0YAAgQNle9pAwQAAAAA"
  //     }
  // }
  loading.value = true;
  const result = await startAnalysis().finally(() => {
    loading.value = false;
  });
  analysisResult.value = result;

  // const timer = setInterval(async () => {
  //   const tempAnalysisResult = await getAnalysisResult(analysisTaskId);
  //   if (tempAnalysisResult.status !== 'processing') {
  //     clearInterval(timer);
  //     analysisResult.value = tempAnalysisResult;
  //     console.log('tempAnalysisResult', tempAnalysisResult);

  //   }
  // }, 1000);

})

</script>

<template>
  <div v-if="loading" class="detail-analysis-page">
    <div class="loading-content">
      <div class="loading-icon"></div>
      <p>面容分析中</p>
      <p>...</p>
    </div>
  </div>
  <div v-else class="detail-analysis-page">
    <!-- 页面内容 -->
    <div class="page-content">
      <template v-if="analysisResultTag">
        <h1 class="page-title">真棒，<br />
          你的颌面非常健康！</h1>
        <!-- 说明文字 -->
        <p class="description">
          16年后，你的长相是这样的
        </p>
      </template>
      <template v-else-if="swiperIndex === 0">
        <h1 class="page-title">啊哦，<br />
          颌面发育似乎不太妙！</h1>
        <!-- 说明文字 -->
        <p class="description">
          16年后，你的长相是这样的
        </p>
      </template>
      <template v-else-if="swiperIndex === 1">
        <h1 class="page-title">但是不用担心，<br />
          矫正后面容会变成这样！</h1>
        <!-- 说明文字 -->
        <p class="description">
          通过科学手段干预，颌面会被修复为：
        </p>
      </template>



      <div class="analysis-result">
        <div class="analysis-success" v-if="analysisResultTag">
          <div class="analysis-success-tips">
            <img src="@/assets/images/analysis-success-tips.png" alt="analysis-success-tips-img" />
            <h3 class="tips-title">健康小贴士: </h3>
            <p class="tips-content">你的长相是这样的你的长相是这样的你的长相是这样的你的长相是这样的</p>
          </div>
          <div class="analysis-success-img">
            <img src="@/assets/images/analysis-success.png" alt="analysis-success" />
          </div>
          <ScanSubscription />
        </div>
        <div class="analysis-failed" v-else>
          <AnalysisFailedSwiper :analysisResult="analysisResult" @slideChange="handleSlideChange" />
          <div class="analysis-failed-content">
            <div v-show="swiperIndex === 0" class="analysis-failed-tips">
              <h3 class="tips-title">问题诊断: </h3>
              <p class="tips-content">你的长相是这样的你的长相是这样的你的长相是这样的你的长相是这样的</p>
            </div>
            <ScanSubscription v-show="swiperIndex === 1" />
          </div>

        </div>
      </div>
    </div>

    <!-- 底部按钮 -->
    <div class="bottom-section">
      <!-- <PrimaryButton text="返回报告" @click="handleReturnReport" /> -->
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped lang="scss">
.detail-analysis-page {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 100px;
  padding-top: max(100px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;

  .loading-content {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;

    .loading-icon {
      margin-bottom: 16px;
    }

    p {
      font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
      font-size: 14px;
      font-weight: 400;
      line-height: 26px;
      color: #000;
      line-height: 1.2;
    }
  }
}

.page-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
  margin-top: 24px;
}

.description {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 16px 0 32px 0;
}

.page-content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  overflow-y: auto;

  .analysis-result {
    display: flex;
    flex-direction: column;
    align-items: center;

    .analysis-success {
      width: 100%;
      height: 100%;
      display: flex;
      flex-direction: column;
      align-items: center;
      position: relative;

      .analysis-success-img {
        width: 540px;
        height: 720px;
        margin-bottom: 60px;

        img {
          width: 100%;
          height: 100%;
          object-fit: cover;
        }
      }

      .analysis-success-tips {
        font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
        font-size: 16px;
        font-weight: 400;
        color: #000;
        position: absolute;
        top: 610px;
        right: 0;
        text-align: center;
        width: 270px;
        background-color: #bcbcbc;
        box-sizing: border-box;
        padding: 20px 12px;
        text-align: left;

        .tips-title {
          font-size: 20px;
          // zoom: 0.65;
          line-height: 1;
          font-weight: 700;
          margin-top: 4px;
          margin-bottom: 12px;
        }

        .tips-content {
          font-size: 20px;
          // zoom: 0.65;
          line-height: 24px;
        }

        img {
          width: 80px;
          position: absolute;
          left: 100px;
          top: -40px;
        }
      }
    }

    .analysis-failed {
      width: 100%;
      height: 100%;

      .analysis-failed-content {
        margin-top: 36px;
        display: flex;
        justify-content: center;
      }

      .analysis-failed-tips {
        font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
        font-size: 16px;
        font-weight: 400;
        color: #000;
        text-align: center;
        width: 100%;
        background-color: #bcbcbc;
        box-sizing: border-box;
        padding: 40px;
        text-align: left;

        .tips-title {
          font-size: 24px;
          font-weight: 700;
          margin-bottom: 4px;
        }

        .tips-content {
          font-size: 20px;
          line-height: 24px;
        }
      }
    }
  }
}

/* 底部按钮 */
.bottom-section {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 24px 0 8px;
}

.logo {
  margin-top: 16px;
}
</style>
