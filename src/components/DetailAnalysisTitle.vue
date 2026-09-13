<script setup lang="ts">
/**
 * 详情分析页顶部"标题/文案区"。
 *
 * 渲染入口 `/detail-analysis` 时页面顶部的差异化文案：
 *   - 健康面型：直接展示 `healthyOpening`（来自 `getDiagnosisCopy(0).opening`）
 *   - 不健康面型 swiperIndex=0：「但是不用担心，矫正后面容会变成这样！」
 *   - 不健康面型 swiperIndex=1：「啊哦，颌面发育似乎不太妙！」
 *
 * 该组件只关心标题展示：
 *   - 装饰图（`text-section-decor`）
 *   - 安全区 + 左右 padding（`.text-section` 容器）
 *   - 反向布局（健康 / 不健康 文案不一样宽）
 *
 * 不关心：swiper 本体、报告区、底部按钮 —— 那些留在 `DetailAnalysisView.vue`。
 */

interface Props {
  /** 健康面型时显示 opening（已从 diagnosisCopy 里取好，组件不做诊断逻辑） */
  healthyOpening: string
  /** 当前面型是否健康 —— true 时整个标题文案切到健康分支 */
  isHealthyFace: boolean
  /** 不健康分支下 swiper 当前页索引（0=预测好面容，1=当前坏面容） */
  swiperIndex: number
}

defineProps<Props>()
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
    <template v-else-if="swiperIndex === 0">
      <div class="text-section text-section--reversed">
        <img src="@/assets/images/common-left.png" alt="" class="text-section-decor" aria-hidden="true" />
        <h1 class="page-title">
          但是不用担心，<br />
          矫正后面容会变成这样！
        </h1>
        <!-- 说明文字 -->
        <!-- <p class="description">通过科学手段干预，颌面会被修复为：</p> -->
      </div>
    </template>
    <template v-else-if="swiperIndex === 1">
      <div class="text-section text-section--reversed">
        <img src="@/assets/images/common-left.png" alt="" class="text-section-decor" aria-hidden="true" />
        <h1 class="page-title">
          啊哦，<br />
          颌面发育似乎不太妙！
        </h1>
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
  flex-wrap: wrap;

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
