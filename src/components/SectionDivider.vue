<script setup lang="ts">
/**
 * 横向分隔线 + 中间文字（典型「标题栏」样式）。
 *
 * 父容器宽度 100%，中间放文字，左右两根线自动平分剩余空间：
 *   - 文字越短，两侧线越长
 *   - 文字越长，两侧线越短
 *
 * 文字通过默认 slot 传入，可以是纯文本，也可以是 `<h3>` / `<p>` 等任意内容。
 * 视觉样式（颜色、线粗、间距、字号）直接改 `<style scoped>` 即可。
 */
</script>

<template>
  <div class="section-divider" role="separator">
    <span class="section-divider-line" aria-hidden="true" />
    <div class="section-divider-text">
      <slot />
    </div>
    <span class="section-divider-line" aria-hidden="true" />
  </div>
</template>

<style scoped lang="scss">
.section-divider {
  display: flex;
  align-items: center;
  width: 100%;
  /* 文字和线之间的留白 —— 改这里即可整段同步 */
  gap: 24px;
}

.section-divider-line {
  /* flex: 1 + flex-basis: 0 让两根线"等长平分剩余空间"，
     父容器减去文字后剩余的宽度两端均分 —— 这就是"线长随文字变化"的核心。 */
  flex: 1 1 0;
  min-width: 0;
  height: 2px;
  background-color: #000;
  border-radius: 1px;
}

.section-divider-text {
  /* 关键：文字自己不参与 grow/shrink，永远保持自然宽度。 */
  flex: 0 0 auto;
  max-width: 100%;

  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 24px;
  line-height: 36px;
  font-weight: 400;
  color: #000;
  text-align: center;
}

/* 极端情况兜底：父容器非常窄、文字不得不换行时，让线和文字都能优雅退化。
   min-width: 0 上面的 .line 已经设了，这里给文字兜一个最小可读宽度。 */
@media (max-width: 360px) {
  .section-divider {
    gap: 12px;
  }

  .section-divider-text {
    font-size: 18px;
    line-height: 28px;
  }
}
</style>
