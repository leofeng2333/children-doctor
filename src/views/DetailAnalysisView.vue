<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import LogoText from '@/components/LogoText.vue'
import { useAnalysisStore } from '@/stores'
import { useImageSplit } from '@/composables/useImageSplit'
import {
  getDiagnosisCopyFromLLMResult,
  getDiagnosisCopy,
  type DiagnosisCopy,
} from '@/utils/diagnosisCopy'

const router = useRouter()

const analysisStore = useAnalysisStore()
const { result: analysisResult, isLoading } = storeToRefs(analysisStore)

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
  return analysisResult.value?.llmAnalysis?.result?.categoryCode === 0
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
 *   - 0 NORMAL              -> 正常面容
 *   - 1 ASYMMETRY           -> 偏颌/大小脸
 *   - 2 ANTERIOR_CROSSBITE  -> 反颌（地包天）
 *   - 3 OPEN_BITE           -> 开颌
 *   - 4 GUMMY_SMILE         -> 露龈笑
 *   - 5 UPPER_PROTRUSION    -> 牙齿前突（龅牙）/ 上颌前突/下颌后缩
 *   - 6 CROWDING            -> 牙列拥挤
 *   - 7 SPACING             -> 牙列稀疏
 */
const diagnosisName = computed(() => diagnosisCopy.value.title)

/** 不健康面型路径：后端生成的矫正后预测图 URL */
const aiImageUrl = computed(
  () =>
    analysisResult.value?.aiAnalysis?.result?.predictions?.futureImageUrl ?? '',
)
/**
 * 切割后两半图片的 URL。
 *
 * 业务约定（与 `AnalysisFailedSwiper.vue` / `ScanSubscription` 保持一致）：
 *   - `leftUrl`  -> 矫正后的"好"面容（swipe 第 1 张、ScanSubscription 订阅图）
 *   - `rightUrl` -> 矫正前的"坏"面容（swipe 第 2 张）
 *
 * 注：原生插件 `DualCamera.splitImage` 返回的 `leftUrl` 实际对应原图左半（坏），
 * `useImageSplit` 内部已交叉赋值，无需在此处再处理。
 */
const { leftUrl: goodImgUrl, rightUrl: badImgUrl } = useImageSplit(
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
      <template v-if="isHealthyFace">
        <h1 class="page-title">{{ healthyCopy.opening }}</h1>
        <!-- 说明文字 -->
        <p class="description">16年后，你的长相是这样的</p>
      </template>
      <template v-else-if="swiperIndex === 0">
        <h1 class="page-title">
          啊哦，<br />
          颌面发育似乎不太妙！
        </h1>
        <!-- 说明文字 -->
        <p class="description">
          根据预判结果，你可能会有
          <span class="diagnosis-name">{{ diagnosisName }}</span>
          的问题，请爸爸妈妈尽早带你去医院详细检查哦！
        </p>
      </template>
      <template v-else-if="swiperIndex === 1">
        <h1 class="page-title">
          但是不用担心，<br />
          矫正后面容会变成这样！
        </h1>
        <!-- 说明文字 -->
        <p class="description">通过科学手段干预，颌面会被修复为：</p>
      </template>

      <div class="analysis-result">
        <div class="analysis-success" v-if="isHealthyFace">
          <div class="analysis-success-tips">
            <img src="@/assets/images/analysis-success-tips.png" alt="analysis-success-tips-img" />
            <h3 class="tips-title">{{ healthyCopy.opening }}</h3>
            <p class="tips-content">{{ healthyCopy.careTips }}</p>
          </div>
          <div class="analysis-success-img">
            <img :src="healthyWholeImgUrl" alt="analysis-success" />
          </div>
            <ScanSubscription v-show="swiperIndex === 1" :good-img-url="healthyWholeImgUrl" />
        </div>
        <div class="analysis-failed" v-else>
          <AnalysisFailedSwiper :analysisResult="analysisResult" @slideChange="handleSlideChange" />
          <div class="analysis-failed-content">
            <div v-show="swiperIndex === 0" class="analysis-failed-tips">
              <h3 class="tips-title">{{ diagnosisCopy.title }}：{{ diagnosisCopy.opening }}</h3>
              <p v-for="(paragraph, idx) in diagnosisCopy.body" :key="idx" class="tips-content"
                style="margin-bottom: 12px">
                {{ paragraph }}
              </p>
              <p v-if="diagnosisCopy.careTips" class="tips-content" style="margin-top: 16px">
                <strong>日常护理小贴士：</strong>{{ diagnosisCopy.careTips }}
              </p>
              <p v-if="diagnosisCopy.habitNote" class="tips-content" style="margin-top: 12px; color: #c0392b">
                {{ diagnosisCopy.habitNote }}
              </p>
            </div>
            <ScanSubscription v-show="swiperIndex === 1" :good-img-url="goodImgUrl" />
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
  padding: 0 90px;
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

.page-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
  margin-top: 24px;
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
  margin: 16px 0 32px 0;

  .diagnosis-name {
    font-weight: 700;
  }
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
        width: 100%;
        max-width: 485px;
        aspect-ratio: 3 / 4;
        margin-bottom: 60px;
        border-radius: 18px;
        overflow: hidden;

        img {
          display: block;
          width: 100%;
          height: 100%;
          max-width: 100%;
          max-height: 100%;
          object-fit: contain;
        }
      }

      .analysis-success-tips {
        font-family:
          'Inter',
          -apple-system,
          BlinkMacSystemFont,
          sans-serif;
        font-size: 16px;
        font-weight: 400;
        color: #000;
        position: absolute;
        top: 610px;
        right: 0;
        text-align: center;
        width: 270px;
        background-color: #FFE361;
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
        background-color: #FF9900;
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
}

.logo {
  margin-top: 16px;
}
</style>
