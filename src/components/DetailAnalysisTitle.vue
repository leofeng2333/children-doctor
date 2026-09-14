<script setup lang="ts">
/**
 * 详情分析页顶部"标题/文案区"。
 *
 * 渲染入口 `/detail-analysis` 时页面顶部的差异化文案：
 *   - isHealthyFace=true  → healthyOpening（与 stage/branch 无关）
 *   - isHealthyFace=false → (branch) × (stage × swiperIndex) 三维映射
 *       branch:
 *         - 'default'(默认分支,D 项 categoryCode 1~7,识别出牙颌面问题)
 *         - 'habit'  (坏习惯分支,H 项 categoryCode=0 + badHabits 非空,
 *                      面型尚未畸形但问卷触发了坏习惯)
 *       stage × swiperIndex（两个 branch 内部结构完全一致,仅文案不同）:
 *         static    —— "根据预测，你可能存在{trouble}的情况哦！"
 *                       {trouble} 由 trouble prop(=diagnosisCopy.title)替换
 *         preview   —— "亲爱的宝贝，你能关注到并解决这个问题吗？"
 *         swiperGood—— "专业治疗改善颌后，16岁时你将长这样"
 *         swiperBad —— "啊哦，颌面发育似乎不太妙！"
 *
 * stage 由父级 DetailAnalysisView 通过 @stage-change 从 DetailAnalysisFailed
 * 镜像而来 —— 本组件只是消费者,不做状态机决策。
 * branch 由父级 DetailAnalysisView 根据 categoryCode / badHabits 计算后传入。
 *
 * 文案集中维护 —— DETAIL_PAGE_COPY.title（src/utils/diagnosisCopy.ts）。
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

/**
 * 不健康分支下，按 (branch) × (stage × swiperIndex) 两维算出当前要展示的标题文案。
 * 坏习惯分支与默认分支共用同一套 stage × swiperIndex 结构,只是文案侧重不同：
 *   - default：D 项(categoryCode 1~7，识别出牙颌面问题)
 *   - habit  ：H 项(categoryCode=0 + badHabits 非空,面型尚未畸形但已有诱因)
 * 阶段切换时机不变(都由 DetailAnalysisFailed 三段式状态机驱动)。
 *
 * 父级 DetailAnalysisView 通过 @stage-change 从 DetailAnalysisFailed 镜像 stage,
 * 通过 prop branch 告知当前是 default 还是 habit —— 本组件不做分支判断。
 */
type FailedStage = 'static' | 'preview' | 'swiper'
/**
 * 不健康分支下的两个 branch —— 数据驱动,加新 branch 只需:
 *   1. 在 DETAIL_PAGE_COPY.title 里加一个同结构分支
 *   2. 在下面 union 里加一个字面量
 *   3. 在 DetailAnalysisView.vue 算出对应判断并把字面量传过来
 */
type TitleBranch = 'default' | 'habit'

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
   * diagnosisCopy.title —— 用于替换 DETAIL_PAGE_COPY.title.{branch}.static 里的
   * {trouble} 占位符（默认分支=具体分类名"偏𬌗"/"反𬌗"等,
   * 坏习惯分支=HABIT_COPY_MAP[first].title 如"不良口腔习惯——吮唇、下颌前伸"）。
   * 仅 stage='static' 时被读取。其他 stage 不消费该 prop。
   */
  trouble?: string
  /**
   * 不健康分支下的文案子分支:
   *   - 'default'(默认):D 项(categoryCode 1~7)的标准标题文案
   *   - 'habit'        :H 项(categoryCode=0 + badHabits 非空)的坏习惯文案
   * 由父级 DetailAnalysisView 根据 categoryCode / badHabits 计算后传入。
   * isHealthyFace=true 时该 prop 不生效。
   */
  branch?: TitleBranch
}

const props = defineProps<Props>()

/** 取 branch 对应的文案块 —— 未传时回落 default,与 props 默认行为对齐 */
const branchCopy = computed(() => {
  const b = props.branch ?? 'default'
  return DETAIL_PAGE_COPY.title[b]
})

/**
 * 占位符 {trouble} 替换器 —— 抽出来给每个 stage 共用。
 *
 * 行为：
 *   - 当 text 含 '{trouble}' 时,用 split/join 把所有占位符替换成 props.trouble
 *   - 当 text 不含 '{trouble}' 时,直接原样返回 —— 没占位符就别动文案,
 *     避免在尾部 / 中部无意义地拼 props.trouble,污染原文
 *
 * 为什么不用 String.replace —— replace 对正则特殊字符（$& / $1 / ...）敏感,
 * split/join 只做字面量切分,更稳。
 *
 * 为什么每个 stage 都走它 —— 在某业务场景下 preview / swiperGood / swiperBad
 * 三段文案里也可能含 {trouble}(比如坏习惯分支的 preview 可写成
 * "{trouble} 正在悄悄改变你的脸型"),不能只让 static 享受占位符机制。
 * 既然 stage 文案都是人维护的,在每个 stage 都做"有无占位符"判断,
 * 作者就可以放心地在任意一段使用占位符。
 *
 * props.trouble ?? '' —— trouble 为 undefined 时退化空串,
 * 避免模板里渲染出 "undefined" 字样。
 */
const applyTrouble = (text: string): string => {
  if (!text.includes('{trouble}')) return text
  // split 出 N+1 段(N 个占位符都被切开,留 N+1 段纯文本)。
  // reduce 把 props.trouble 插回每个切缝里,支持单 / 多占位符通用场景。
  // props.trouble ?? '' —— undefined 退化为空串,防止模板里出现 "undefined"。
  return text
    .split('{trouble}')
    .reduce(
      (acc, segment, idx, arr) =>
        idx < arr.length - 1
          ? acc + segment + (props.trouble ?? '')
          : acc + segment,
      '',
    )
}

/**
 * 不健康分支下，按 stage × swiperIndex 算出当前要展示的标题文案。
 *
 * computed 缓存结果 —— 4 个分支都是同步字符串替换,没有 IO,
 * 父级 stage/swiperIndex 任一变化才重算。
 *
 * 单一职责：只做"(branch) × (stage × swiperIndex) → 文案"的映射,
 * stage 推进 / swiper 翻页 的逻辑都在其它组件里。
 *
 * 4 个分支全部过 applyTrouble —— 任何一段文案里若含 {trouble} 都自动替换,
 * 不含则原文返回。
 */
const failedTitle = computed(() => {
  if (props.stage === 'static') {
    return applyTrouble(branchCopy.value.static)
  }
  if (props.stage === 'preview') {
    return applyTrouble(branchCopy.value.preview)
  }
  if (props.stage === 'swiper') {
    return props.swiperIndex === 0
      ? applyTrouble(branchCopy.value.swiperGood)
      : applyTrouble(branchCopy.value.swiperBad)
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
