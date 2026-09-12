<script setup lang="ts">
/**
 * 全局开发者入口触发器：fixed 在所有页面右上角，双击打开 DevToolsDialog。
 *
 * <p>区域大小刻意保持与 WelcomeView 原 .admin-area 一致（225x225 透明 IconButton +
 * 同样的右上角 padding），保留用户在首页形成的肌肉记忆。
 *
 * <p>IconButton 内部已经用 300ms 阈值模拟双击，所以这里直接监听 dblclick 即可。
 */
import IconButton from './IconButton.vue'

const emit = defineEmits<{
  open: []
}>()

const handleDoubleClick = () => emit('open')
</script>

<template>
  <div class="dev-launcher">
    <IconButton class="dev-launcher-button" aria-label="开发者入口" @dblclick="handleDoubleClick" />
  </div>
</template>

<style scoped lang="scss">
.dev-launcher {
  position: fixed;
  top: max(20px, env(safe-area-inset-top));
  right: max(20px, env(safe-area-inset-right));
  z-index: 100;
  pointer-events: auto;
}

.dev-launcher-button {
  // 由父级 .dev-launcher 控制定位，不走默认 absolute
  position: static;
}
</style>
