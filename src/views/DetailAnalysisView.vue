<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import LogoText from '@/components/LogoText.vue'
import DetailAnalysisTitle from '@/components/DetailAnalysisTitle.vue'
import DetailAnalysisSuccess from '@/components/DetailAnalysisSuccess.vue'
import { useAnalysisStore } from '@/stores'
import { useImageSplit } from '@/composables/useImageSplit'
import {
  getDiagnosisCopyFromLLMResult,
  getDiagnosisCopy,
  DETAIL_PAGE_COPY,
  type DiagnosisCopy,
} from '@/utils/diagnosisCopy'
// path-current: HiTi init/release 现在由 router.beforeEach 守卫统一管理
// （见 src/router/index.ts），进入 /detail-analysis 自动 initForPage，
// 离开到任意其它路由（含 capture-intro / capture）自动 releaseForPage。
// 不再在 page-level 手动调用 printPageMounted / printPageUnmounted。

const router = useRouter()

const analysisStore = useAnalysisStore()
const { result: analysisResult, isLoading } = storeToRefs(analysisStore)

// （已移除 onMounted / onBeforeUnmount / onUnmounted 的 printPageMounted 调用 ——
//  改由 router.beforeEach 统一管理 HiTi USB 占用周期，避免在 capture 重拍时
//  HiTi 仍占着 USB bus 与 UVC camera 竞争。）

const analysisCompleted = computed(() => {
  return !!analysisResult.value
})

/**
 * 当前面型是否健康（正常面容）。
 *
 * 判断标准：后端 `llmAnalysis.result.categoryCode === 0` (NORMAL)。
 *
 * 与 `diagnosisCopy` 共用同一份 `categoryCode` 读取路径，
 * 通过 `getDiagnosisCopyFromLLMResult` 的解析规则保证一致性：
 *  - 合法整数 0~7 命中 → 用对应分类
 *  - 缺失 / 非法值 → 视为 NORMAL
 *
 * 但为了避免误判健康分支（风险较高），这里**直接读 categoryCode**，
 * 只在显式为 0 时才视为健康；其余情况（含缺失）一律视为不健康。
 */
const isHealthyFace = computed(() => {
  return true;
  // return analysisResult.value?.llmAnalysis?.result?.categoryCode === 0
})

const handleReturnReport = () => {
  router.back()
}

const swiperIndex = ref(0)

const handleSlideChange = (index: number) => {
  swiperIndex.value = index
}

/** 根据后端 categoryCode 优先 / issues 兜底 获取的诊断文案（牙性优先） */
const diagnosisCopy = computed<DiagnosisCopy>(() => {
  const llmResult = analysisResult.value?.llmAnalysis?.result
  return getDiagnosisCopyFromLLMResult(llmResult)
})

/** 健康面型文案 */
const healthyCopy = computed<DiagnosisCopy>(() => getDiagnosisCopy(0))

/**
 * 当前不健康分支对应的中文章节名（与 diagnosisCopy.title 一致）
 *   - 0 NORMAL              -> 正常
 *   - 1 ASYMMETRY           -> 偏𬌗
 *   - 2 ANTERIOR_CROSSBITE  -> 反𬌗
 *   - 3 OPEN_BITE           -> 开𬌗
 *   - 4 GUMMY_SMILE         -> 露龈笑
 *   - 5 UPPER_PROTRUSION    -> 前突
 *   - 6 CROWDING            -> 牙列拥挤
 *   - 7 SPACING             -> 牙列稀疏
 */

/** 不健康面型路径：后端生成的矫正后预测图 URL */
const aiImageUrl = computed(
  () =>
    analysisResult.value?.aiAnalysis?.result?.predictions?.futureImageUrl ?? '',
)
/**
 * 矫正后的"好"面容 URL。
 *
 * 业务约定（与 `AnalysisFailedSwiper.vue` / `ScanSubscription` 保持一致）：
 *   - 此 URL 渲染在 swiper 第 1 张（矫正后好面容），同时复用为
 *     - 不健康分支 `ScanSubscription` 的订阅图
 *     - 健康分支 `healthyWholeImgUrl`（避免再调一次 native splitImage）
 *
 * 注：原生插件 `DualCamera.splitImage` 返回的 `leftUrl` 实际对应原图左半（坏），
 * `useImageSplit` 内部已交叉赋值；这里只取交叉后代表"好面容"的一侧。
 */
const { leftUrl: goodImgUrl } = useImageSplit(
  () => aiImageUrl.value,
  0.5,
  { inset: 20 },
)

/**
 * 健康面型图：复用不健康分支那次 splitImage 的产物（goodImgUrl）。
 * 该图已经是 inset 后的右半（宽度 = halfWidth - inset，比原图窄），
 * 满足"经过分割 + 宽度变小"的诉求，又不增加额外 native 调用。
 */
const healthyWholeImgUrl = computed(() => goodImgUrl.value)
</script>

<template>
  <div v-if="isLoading" class="detail-analysis-page">
    <div class="loading-content">
      <div class="loading-icon"></div>
      <p>面容分析中</p>
      <p>...</p>
    </div>
  </div>
  <div v-else class="detail-analysis-page">
    <!-- 页面内容 -->
    <div class="page-content">
      <DetailAnalysisTitle :is-healthy-face="isHealthyFace" :swiper-index="swiperIndex"
        :healthy-opening="healthyCopy.opening" />

      <div class="analysis-result">
        <DetailAnalysisSuccess
          v-if="isHealthyFace"
          :copy="healthyCopy"
          :img-url="healthyWholeImgUrl"
        />
        <div class="analysis-failed" v-else>
          <AnalysisFailedSwiper :analysisResult="analysisResult" @slideChange="handleSlideChange" />
          <div class="analysis-failed-content">
            <div v-show="swiperIndex === 0">
              <p class="failed-content-handle-img-tips">诊断完成，可以通过以下方式获取照片或结束体验！</p>
              <ScanSubscription :good-img-url="goodImgUrl" />
            </div>
            <div v-show="swiperIndex === 1">
              <div class="analysis-failed-tips analysis-failed-tips--primary">
                <h3 class="tips-title">{{ diagnosisCopy.title }}：{{ diagnosisCopy.opening }}</h3>
                <p v-for="(paragraph, idx) in diagnosisCopy.bodyPrimary" :key="idx" class="tips-content">
                  {{ paragraph }}
                </p>
              </div>
              <div class="analysis-failed-tips analysis-failed-tips--secondary" style="margin-top: 16px">
                <img src="@/assets/images/analysis-success-tips.png" alt="analysis-success-tips-img" />
                <p v-if="diagnosisCopy.careTips" class="tips-content">
                  <strong>{{ DETAIL_PAGE_COPY.careTipsPrefix }}</strong>{{ diagnosisCopy.careTips }}
                </p>
              </div>
            </div>
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

  background: #ffffff;
  display: flex;
  flex-direction: column;
  overflow: hidden;

  .loading-content {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    /* 原 .detail-analysis-page 的顶/底 padding 迁移到这里（水平方向不需要，
       因为 loading-content 本身 100% 宽）。 */
    padding-top: max(100px, env(safe-area-inset-top));
    padding-bottom: calc(40px + env(safe-area-inset-bottom));

    .loading-icon {
      margin-bottom: 16px;
    }

    p {
      font-family:
        'Inter',
        -apple-system,
        BlinkMacSystemFont,
        sans-serif;
      font-size: 14px;
      font-weight: 400;
      line-height: 26px;
      color: #000;
      line-height: 1.2;
    }
  }
}

.page-content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  background-color: #FFE361;
  /* padding 已全部下放到 .text-section / .analysis-result / .logo
     其中 .text-section 相关样式已迁移到 <DetailAnalysisTitle />。 */

  .analysis-result {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    background-color: #ffffff;
    margin-top: 36px;
    /* padding 已下放到 <DetailAnalysisSuccess /> 的 .analysis-success：
       - 70px 顶部留白 + 90px 左右内边距由子组件自己声明
       - 这样父级只承担"白底圆角容器"职责，不掺杂子组件的呼吸感 */
    border-radius: 78px 78px 0 0;

    // .analysis-success 样式已迁移到 <DetailAnalysisSuccess />。

    .analysis-failed {
      /* flex 列容器 .analysis-result 内的剩余高度填充 —— 不能用 height: 100%，
         因为父高度是经 flex-grow 解算出来的，flex 子项百分比高度不可靠。 */
      flex: 1;
      width: 100%;
      display: flex;
      flex-direction: column;
      background: transparent;

      .analysis-failed-content {
        margin-top: 36px;
        display: flex;
        justify-content: center;
      }

      .analysis-failed-tips {
        font-family:
          'Inter',
          -apple-system,
          BlinkMacSystemFont,
          sans-serif;
        font-size: 16px;
        font-weight: 400;
        color: #000;
        text-align: center;
        width: 100%;
        background-color: #FFE361;
        box-sizing: border-box;
        padding: 32px 20px;
        text-align: left;
        position: relative;

        .tips-title {
          font-size: 24px;
          font-weight: 700;
          margin-bottom: 4px;
        }

        .tips-content {
          font-size: 20px;
          line-height: 24px;
          margin-bottom: 12px;

          &:last-child {
            margin-bottom: 0;
          }
        }
      }

      .failed-content-handle-img-tips {
        font-size: 30px;
        line-height: 36px;
        font-weight: 400;
        margin-bottom: 24px;
      }

      .analysis-failed-tips--secondary {
        background-color: #FFC28B;

        img {
          width: 80px;
          position: absolute;
          left: 50%;
          transform: translateX(-50%);
          top: -40px;
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
  /* padding 已下放到 .logo */
}

.logo {
  margin-top: 16px;
  /* 原 .detail-analysis-page / .bottom-section 的左/右/底 padding 迁移到这里 */
  padding: 0 90px calc(40px + env(safe-area-inset-bottom));
}
</style>
