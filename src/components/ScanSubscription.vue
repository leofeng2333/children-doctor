<script setup lang="ts">
import { createSubscriptionTask, getSubscriptionStatus } from '@/utils/service';
import { onMounted, ref } from 'vue';

const hadSubscription = ref(false);

const qrcodeUrl = ref('');

onMounted(async () => {
  const response = await createSubscriptionTask();
  qrcodeUrl.value = response.qrcodeUrl;
  console.log('response', response);

  const timer = setInterval(async () => {
    const tempHadSubscription = await getSubscriptionStatus(response.followTaskId);
    console.log('tempHadSubscription', tempHadSubscription);
    if (tempHadSubscription.status === 1) {
      clearInterval(timer);
      hadSubscription.value = true;
    }

    // hadSubscription.value = tempHadSubscription;
    // if (tempHadSubscription) {
    // }
  }, 1000);
})
</script>

<template>
  <div v-if="!hadSubscription" class="scan-container">
    <div class="qrcode-container">
      <img :src="qrcodeUrl" alt="qrcode" />
    </div>
    <div class="subscription-container">
      扫码关注<br />
      带走高清照片
    </div>
  </div>
  <div v-else class="had-subscription-container">
    <div class="qrcode-container">
      <img src="https://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=mock_ticket_follow_df491d7637184388_1776492749873"
        alt="qrcode" />
    </div>

    <div class="had-subscription-content">
      <div class="scan-save-photo-container">
        <img src="@/assets/arrow-left.svg" alt="scan-save-photo" />
        扫一扫保存照片
      </div>
      <button class="status-tag-btn">完成诊断</button>
    </div>
  </div>
</template>

<style scoped lang="scss">
.scan-container {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.qrcode-container {
  width: 185px;
  height: 185px;

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
}

.subscription-container {
  font-size: 32px;
  line-height: 42px;
  margin-top: 12px;
  text-align: center;
}

.had-subscription-container {
  display: flex;

  .had-subscription-content {
    margin-left: 30px;
    display: flex;
    flex-direction: column;
    justify-content: center;

    .scan-save-photo-container {
      font-size: 32px;
      line-height: 52px;
      display: flex;
      align-items: center;
      padding-left: 12px;
      margin-bottom: 6px;

      img {
        width: 24px;
        height: 24px;
        margin-right: 12px;
      }
    }
  }

  .status-tag {
    padding: 8px 18px;
    color: #000;
    font-size: 14px;
    background: #D9D9D9;
    border-radius: 50px;
    line-height: 1;
    box-sizing: border-box;
    min-height: 32px;
    min-width: 48px;
  }

  .status-tag-btn {
    padding: 10px 18px;
    color: #000;
    font-size: 32px;
    line-height: 52px;
    font-weight: 700;
    background: #D9D9D9;
    border-radius: 50px;
    line-height: 1;
    box-sizing: border-box;
    min-height: 70px;
    min-width: 369px;
    width: 160px;
    border-color: transparent;
  }
}
</style>
