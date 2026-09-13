<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    text?: string
    disabled?: boolean
    loading?: boolean
    color?: string
  }>(),
  {
    color: '#000',
  },
)

const emit = defineEmits<{
  click: []
}>()

const handleClick = () => {
  if (props.disabled || props.loading) return
  emit('click')
}
</script>

<template>
  <button
    class="primary-btn"
    :class="{ disabled: disabled }"
    :disabled="disabled || loading"
    @click="handleClick"
  >
    <span v-if="loading" class="loading-dot"></span>
    <slot v-else>
      <span>{{ text }}</span>
    </slot>
  </button>
</template>

<style scoped>
.primary-btn {
  width: 90%;
  margin: 0 auto;
  background: #ff9900;
  color: v-bind(color);
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  border: none;
  border-radius: 50px;
  line-height: 1;
  padding: 34px 60px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  /* 触控反馈要快 —— 原来 transition: all 0.3s 在触屏点按 (<120ms) 时
     按下动画根本跑不完就回弹,等于「没反馈」。改成按属性分写 0.12s,
     既快到有瞬时感,松手也不会突兀。 */
  transition:
    transform 0.12s ease,
    box-shadow 0.12s ease,
    background-color 0.15s ease;

  & > span {
    font-weight: 700;
  }
}

.primary-btn:active:not(.disabled) {
  /* 同时下沉 + 变深 —— 单一 signal (只缩放) 在大按钮上太弱,
     加上背景色变深 (#ff9900 → #e68a00) 才有「真的按下去了」的感觉。

     纯 CSS 实现: 完全依赖 :active 伪类触发。Capacitor 部分 WebView 上
     :active 触发时机可能不稳,若实测仍有「按下没反应」的情况,
     这一层 CSS 没有绕过的办法 (浏览器 API 限制),届时需回到 JS 监听
     touch 事件的方案。 */
  transform: scale(0.95);
  background-color: #e68a00;
  box-shadow: 0 2px 6px rgba(255, 153, 0, 0.25);
}

.primary-btn.disabled {
  background: #bcbcbc;
  color: #fff;
  box-shadow: none;
  cursor: not-allowed;
}

.loading-dot {
  display: inline-block;
  width: 18px;
  height: 18px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
