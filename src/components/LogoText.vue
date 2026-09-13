<script setup lang="ts">
import logoLeft from '@/assets/images/logo-left.png'
import logoRight from '@/assets/images/logo-right.png'
</script>

<template>
  <div class="logo">
    <!-- <img class="logo-left" :src="logoLeft" alt="" />
    <img class="logo-right" :src="logoRight" alt="" /> -->
    <p class="logo-content">以上分析仅为AI系统根据现场拍摄照片分析得出，不作为医学诊断依据，具体情况请以专业医生检查诊断为准。
      请勿仅凭本结果自行制定治疗方案。</p>
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

.logo-right {
  margin-left: 20px;
}

.logo-content {
  font-size: 16px;
  line-height: 20px;
  color: #8A8A8A;
  text-align: center;
}
</style>
