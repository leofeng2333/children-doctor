<script setup lang="ts">
withDefaults(defineProps<{
  text: string
  disabled?: boolean
  color?: string
}>(), {
  color: '#000'
})

defineEmits<{
  click: []
}>()
</script>

<template>
  <button class="primary-btn" @click="$emit('click')">
    {{ text }}
  </button>
</template>

<style scoped>
.primary-btn {
  width: 90%;
  margin: 0 auto;
  background: #FF9900;
  color: v-bind(color);
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 700;
  border: none;
  border-radius: 50px;
  padding: 12px 30px;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(255, 153, 0, 0.4);
  transition: all 0.3s ease;
}

.primary-btn:active {
  transform: scale(0.98);
  box-shadow: 0 4px 12px rgba(255, 153, 0, 0.3);
}

.primary-btn:disabled {
  background: #BCBCBC;
  color: #fff;
  box-shadow: none;
  cursor: not-allowed;
}
</style>
