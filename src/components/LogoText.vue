<script setup lang="ts">
import logoLeft from '@/assets/images/logo-left.png'
import logoRight from '@/assets/images/logo-right.png'
</script>

<template>
  <div class="logo">
    <img class="logo-left" :src="logoLeft" alt="" />
    <img class="logo-right" :src="logoRight" alt="" />
  </div>
</template>

<style scoped>
.logo {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.logo-left,
.logo-right {
  height: 48px;
  width: auto;
  display: block;
}

.logo-left {
  margin-left: 30px;
}
</style>
