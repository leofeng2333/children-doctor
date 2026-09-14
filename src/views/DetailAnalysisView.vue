<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import LogoText from '@/components/LogoText.vue'
import DetailAnalysisTitle from '@/components/DetailAnalysisTitle.vue'
import DetailAnalysisSuccess from '@/components/DetailAnalysisSuccess.vue'
import DetailAnalysisFailed, {
  type FailedStage,
} from '@/components/DetailAnalysisFailed.vue'
import { useAnalysisStore } from '@/stores'
import { useImageSplit } from '@/composables/useImageSplit'
import {
  getDiagnosisCopyFromLLMResult,
  getDiagnosisCopy,
  HabitCode,
  type DiagnosisCopy,
} from '@/utils/diagnosisCopy'
// path-current: HiTi init/release 现在由 router.beforeEach 守卫统一管理
// （见 src/router/index.ts），进入 /detail-analysis 自动 initForPage，
// 离开到任意其它路由（含 capture-intro / capture）自动 releaseForPage。
// 不再在 page-level 手动调用 printPageMounted / printPageUnmounted。

const router = useRouter()
const route = useRoute()

const analysisStore = useAnalysisStore()
const { result: analysisResult, isLoading, badHabits } = storeToRefs(analysisStore)

// （已移除 onMounted / onBeforeUnmount / onUnmounted 的 printPageMounted 调用 ——
//  改由 router.beforeEach 统一管理 HiTi USB 占用周期，避免在 capture 重拍时
//  HiTi 仍占着 USB bus 与 UVC camera 竞争。）

const analysisCompleted = computed(() => {
  return !!analysisResult.value
})

/**
 * 手动 mock 场景入口 —— 通过 URL query 触发，用于在 dev / 测试环境
 * 跳过拍照 + 后端调用，直接验证 detail-analysis 的分支渲染逻辑。
 *
 * 使用方式：
 *   /detail-analysis               → 正常流程
 *   /detail-analysis?mock=bad-habit → categoryCode=0 + 一个坏习惯,
 *                                     触发 DetailAnalysisFailed 的
 *                                     坏习惯分支(标题对应「面型正常」
 *                                     文案,下方走 HABIT_COPY_MAP + 卡通图轮播)。
 *                                     通过 ?habit=B/C/D 切换坏习惯编码,
 *                                     默认 B(对应「龅牙/开颌」)。
 *
 * 该分支用 `import.meta.env.DEV` 隔离:prod build 死代码消除,
 * 不会泄露到生产 bundle 中(同时这里要求已经进到 /detail-analysis 页,
 * 不会主动发起后端调用,所以即便误开也只会展示 mock 数据)。
 */
onMounted(() => {
  if (!import.meta.env.DEV) return
  if (route.query.mock !== 'bad-habit') return

  // categoryCode === 0 + 至少一个坏习惯 = 触发 isHealthyFace === false
  // 但 DetailAnalysisFailed 内部会进入坏习惯分支的判定。
  const habitParam = (route.query.habit as string | undefined)?.toUpperCase()
  const habit: HabitCode = ((): HabitCode => {
    switch (habitParam) {
      case 'A':
        return HabitCode.HABIT_ANTIJOINT
      case 'B':
        return HabitCode.HABIT_PROTRUSION
      case 'C':
        return HabitCode.HABIT_BREATH
      case 'D':
        return HabitCode.HABIT_ASYMMETRY
      default:
        return HabitCode.HABIT_PROTRUSION
    }
  })()

  // 直接 setBadHabits 走 store action,与生产路径同源。
  analysisStore.setBadHabits([habit])
  // categoryCode 显式置 0,触发「面型正常但有坏习惯」分支;
  // 其余字段保留 undefined,DetailAnalysisView 的可选链天然兼容。
  // 用 type assertion 跨过 `result: ref<any>` 的弱类型提示。
  analysisResult.value = {
    llmAnalysis: { result: { categoryCode: 0 } },
  } as any

  console.warn(
    '[DetailAnalysisView] mock=bad-habit 已启用, habit=',
    habit,
    '仅 DEV 模式生效,不会进生产。',
  )
})

/**
 * 当前面型是否健康（正常面容）。
 *
 * 判断标准（同时满足两条）：
 *   1. 后端 `llmAnalysis.result.categoryCode === 0` (NORMAL)
 *   2. 问卷回答中没有触发任何坏习惯（badHabits 为空）
 *
 * 任意一条不满足即视为不健康：
 *   - categoryCode > 0  → 已识别出牙颌面问题
 *   - categoryCode === 0 但坏习惯非空 → 面型尚未畸形,但已有导致畸形
 *     的不良习惯,需要进入坏习惯分支走 HABIT_COPY_MAP + 卡通图轮播。
 *     这条与 `DetailAnalysisFailed` 内部 `categoryCode===0 && badHabits.length>0`
 *     的分支保持一致,否则会出现「DetailAnalysisTitle 走 healthy 文案、
 *      DetailAnalysisFailed 渲染坏习惯图」互相矛盾的状态。
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
  return (
    analysisResult.value?.llmAnalysis?.result?.categoryCode === 0 &&
    badHabits.value.length === 0
  )
})

/**
 * 不健康分支下是否是「坏习惯子分支」（H 项）：
 *   categoryCode === 0（面型尚未畸形）+ badHabits 非空（问卷触发了坏习惯）
 *
 * 与 DetailAnalysisFailed 内部 `categoryCode===0 && badHabits.length>0`
 * 的条件严格保持一致 —— 标题组件与详情组件必须对同一份数据给出一致结论,
 * 否则会出现「标题显示牙颌面问题文案,但详情区展示坏习惯图」互相矛盾的状态。
 *
 * 该计算仅在 isHealthyFace=false 时才会被 DetailAnalysisTitle 读取
 * （健康分支模板走 healthyOpening,与 branch 无关）。
 * 这里用 computed 而不是直接写三元表达式 —— 保留 vue template 的计算缓存,
 * 且 badHabits / categoryCode 任一变化才重算。
 */
const isHabitBranch = computed(() => {
  return (
    analysisResult.value?.llmAnalysis?.result?.categoryCode === 0 &&
    badHabits.value.length > 0
  )
})

const handleReturnReport = () => {
  router.back()
}

const swiperIndex = ref(0)

const handleSlideChange = (index: number) => {
  swiperIndex.value = index
}

/**
 * 镜像 DetailAnalysisFailed 的三段式状态机 stage。
 *
 * 不在本视图里"推进" stage —— 推进逻辑由 DetailAnalysisFailed 的 advance()
 * 自己拥有（因为那是它的内部状态机责任），本视图只通过 @stage-change
 * 镜像一份，让 DetailAnalysisTitle 可以 prop 接收并切标题文案。
 *
 * 这样 DetailAnalysisFailed 仍是 stage 状态的 single source of truth,
 * 标题只是"读"它的镜像，不会和 swiper 推进逻辑耦合。
 */
const stage = ref<FailedStage>('static')

const handleStageChange = (newStage: FailedStage) => {
  stage.value = newStage
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
      <!--
        DetailAnalysisTitle 接收 (branch) × (stage × swiperIndex) 三维度信号：
          - isHealthyFace=true → healthyOpening（与 stage / branch 无关）
          - isHealthyFace=false:
              branch='default' (D 项 categoryCode 1~7):
                stage='static'              → DETAIL_PAGE_COPY.title.default.static
                stage='preview'             → DETAIL_PAGE_COPY.title.default.preview
                stage='swiper'+index=0      → DETAIL_PAGE_COPY.title.default.swiperGood
                stage='swiper'+index=1      → DETAIL_PAGE_COPY.title.default.swiperBad
              branch='habit'   (H 项 categoryCode=0 + badHabits 非空):
                阶段映射同上,文案取 DETAIL_PAGE_COPY.title.habit.*
        trouble 传 diagnosisCopy.title（默认分支=分类名 "偏𬌗"/"反𬌗" 等,
        坏习惯分支=HABIT_COPY_MAP[first].title 如 "不良口腔习惯——吮唇、下颌前伸"）。
        DetailAnalysisTitle 内部用 split('{trouble}') 切文案 + 拼接替换占位符。
        同一份文案里没有 {trouble} 时 split 退化为单段,等价于原文 —— 不强制使用占位符。
      -->
      <DetailAnalysisTitle
        :is-healthy-face="isHealthyFace"
        :stage="stage"
        :swiper-index="swiperIndex"
        :branch="isHabitBranch ? 'habit' : 'default'"
        :healthy-opening="healthyCopy.opening"
        :trouble="diagnosisCopy.title"
      />

      <div class="analysis-result">
        <DetailAnalysisSuccess v-if="isHealthyFace" :copy="healthyCopy" :img-url="healthyWholeImgUrl" />
        <DetailAnalysisFailed v-else :analysis-result="analysisResult" :diagnosis-copy="diagnosisCopy"
          :good-img-url="goodImgUrl" :bad-habits="badHabits" @slide-change="handleSlideChange" @stage-change="handleStageChange" />
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

    // .analysis-success / .analysis-failed 样式已分别迁移到
    // <DetailAnalysisSuccess /> / <DetailAnalysisFailed />。
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
