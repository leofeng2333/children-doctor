<script setup lang="ts">
import { ref } from 'vue'

const DOUBLE_TAP_DELAY = 300 // ms

const lastTapTime = ref(0)

const handleTap = () => {
  const now = Date.now()
  if (now - lastTapTime.value < DOUBLE_TAP_DELAY) {
    lastTapTime.value = 0
    emit('dblclick')
  } else {
    lastTapTime.value = now
  }
}

const emit = defineEmits<{
  dblclick: []
}>()
</script>

<template>
  <button
    class="icon-button"
    type="button"
    aria-label="功能按钮"
    @click="handleTap"
  >
    <slot />
  </button>
</template>

<style scoped lang="scss">
.icon-button {
  width: 225px;
  height: 225px;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  -webkit-tap-highlight-color: transparent;
  touch-action: manipulation;
  user-select: none;
}
</style>
