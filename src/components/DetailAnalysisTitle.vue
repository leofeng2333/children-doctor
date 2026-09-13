<script setup lang="ts">
/**
 * 详情分析页顶部"标题/文案区"。
 *
 * 渲染入口 `/detail-analysis` 时页面顶部的差异化文案，由两个维度共同决定：
 *   - isHealthyFace: 健康面型直接展示 healthyOpening
 *   - 不健康面型：stage × swiperIndex 双维度信号切到 DETAIL_PAGE_COPY.title
 *
 * 文案集中维护 —— DETAIL_PAGE_COPY.title（src/utils/diagnosisCopy.ts）：
 *   static     —— "根据预测，你可能存在{trouble}的情况哦！"，{trouble} 由
 *                trouble prop（=diagnosisCopy.title, e.g. "偏𬌗"）替换。
 *   preview    —— "亲爱的宝贝，你能关注到并解决这个问题吗？"
 *   swiperGood —— "专业治疗改善颌后，16岁时你将长这样"
 *   swiperBad  —— "啊哦，颌面发育似乎不太妙！"
 *
 * stage 由父级 DetailAnalysisView 通过 @stage-change 从 DetailAnalysisFailed
 * 镜像而来 —— 本组件只是消费者,不做状态机决策。
 *
 * 该组件只关心标题展示：
 *   - 装饰图（`text-section-decor`）
 *   - 安全区 + 左右 padding（`.text-section` 容器）
 *   - 反向布局（健康 / 不健康 文案不一样宽）
 *
 * 不关心：swiper 本体、报告区、底部按钮 —— 那些留在 `DetailAnalysisView.vue`。
 */
import { computed } from 'vue'
import { DETAIL_PAGE_COPY } from '@/utils/diagnosisCopy'

type FailedStage = 'static' | 'preview' | 'swiper'

interface Props {
  /** 健康面型时显示 opening（已从 diagnosisCopy 里取好，组件不做诊断逻辑） */
  healthyOpening: string
  /** 当前面型是否健康 —— true 时整个标题文案切到健康分支 */
  isHealthyFace: boolean
  /**
   * 不健康分支三段式状态机的当前阶段（仅 isHealthyFace=false 时有效）。
   * isHealthyFace=true 时该 prop 可不传 / 传任意值,组件内部不会读取。
   */
  stage?: FailedStage
  /**
   * 不健康分支下 swiper 当前页索引（仅 stage='swiper' 时实际生效）。
   * stage='static' / 'preview' 时 swiper 还未挂载,swiperIndex 无意义,
   * 这里仍可传 0（与 DetailAnalysisView 的初始值一致）以满足类型约束。
   */
  swiperIndex?: number
  /**
   * diagnosisCopy.title —— 用于替换 DETAIL_PAGE_COPY.title.static 里的
   * {trouble} 占位符（"偏𬌗"/"反𬌗"/"牙列拥挤"等）。
   * 仅 stage='static' 时被读取。
   */
  trouble?: string
}

const props = defineProps<Props>()

/**
 * 不健康分支下，按 stage × swiperIndex 算出当前要展示的标题文案。
 *
 * computed 缓存结果 —— 4 个分支都是同步字符串替换,没有 IO,
 * 父级 stage/swiperIndex 任一变化才重算。
 *
 * 单一职责：只做"stage × swiperIndex → 文案"的映射,
 * stage 推进 / swiper 翻页 的逻辑都在其它组件里。
 */
const failedTitle = computed(() => {
  if (props.stage === 'static') {
    // {trouble} 占位符替换 —— 用 split/join 而不是 .replace,
    // 是因为如果文案里出现多个 {trouble},两者效果一致;
    // 且 .replace 对正则特殊字符敏感（虽然这里不会,但 split/join 更稳）。
    const parts = DETAIL_PAGE_COPY.title.static.split('{trouble}')
    return [parts[0], props.trouble ?? '', parts[1]].join('')
  }
  if (props.stage === 'preview') {
    return DETAIL_PAGE_COPY.title.preview
  }
  if (props.stage === 'swiper') {
    return props.swiperIndex === 0
      ? DETAIL_PAGE_COPY.title.swiperGood
      : DETAIL_PAGE_COPY.title.swiperBad
  }
  // 兜底：理论上 isHealthyFace=false 时 stage 一定有值,
  // 但万一未来加了新 stage 且忘了改这里,渲染空字符串而不是报错。
  return ''
})
</script>

<template>
  <div class="detail-analysis-title">
    <template v-if="isHealthyFace">
      <div class="text-section">
        <img src="@/assets/images/common-left.png" alt="" class="text-section-decor" aria-hidden="true" />
        <div class="text-section-content">
          <h1 class="page-title">{{ healthyOpening }}</h1>
          <!-- 说明文字 -->
          <!-- <p class="description">16年后，你的长相是这样的</p> -->
        </div>
      </div>
    </template>
    <template v-else>
      <!--
        不健康分支：根据 failedTitle computed 显示当前阶段对应的文案。
        与原 swiperIndex===0/===1 二分模板相比,这里收敛到一段,
        文案由 DETAIL_PAGE_COPY.title 统一维护,改文案只需改那个对象。
      -->
      <div class="text-section text-section--reversed">
        <img src="@/assets/images/common-left.png" alt="" class="text-section-decor" aria-hidden="true" />
        <h1 class="page-title">{{ failedTitle }}</h1>
      </div>
    </template>
  </div>
</template>

<style scoped lang="scss">
.detail-analysis-title {
  background-color: transparent;
}

.page-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 55px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
  // margin-top: 24px;
}

/* 健康 / 不健康分支统一的"文案区"布局容器：
   - 顶部安全区（max(100px, env(safe-area-inset-top))）+ 左右 90px padding
   - 健康分支左侧带 .text-section-decor 装饰图，不健康分支无图 */
.text-section {
  display: flex;
  align-items: flex-start;
  gap: 32px;
  padding: max(100px, env(safe-area-inset-top)) 0 0 58px;

  .text-section-decor {
    width: 140px;
    height: auto;
    flex-shrink: 0;
    display: block;
  }

  .text-section-content {
    flex: 1 1 auto;
    min-width: 0;
  }
}

.text-section--reversed {
  // flex-wrap: wrap;

  .page-title {
    flex: 1 1 auto;
    min-width: 0;
    align-self: flex-start;
    display: flex;
    align-items: center;
    font-size: 55px;
    line-height: 80px;
  }
}
</style>
