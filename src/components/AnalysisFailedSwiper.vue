<script setup lang="ts">
import Swiper from 'swiper';
import 'swiper/css'
import 'swiper/css/effect-cards';

// import './style.css';

// import required modules
import { EffectCards } from 'swiper/modules';
import { onMounted, onUnmounted, ref } from 'vue';

const emit = defineEmits(['slideChange']);

const swiperInstance = ref<any>(null);

const swiperIndex = ref(0);

const onSlideChange = (e: any) => {
  console.log('slide change', e.activeIndex);
  swiperIndex.value = e.activeIndex;
  emit('slideChange', e.activeIndex);
}

onMounted(() => {
  swiperInstance.value = new Swiper('.detail-swiper', {
    effect: 'cards',
    grabCursor: true,
    modules: [EffectCards],
  })

  swiperInstance.value.on('slideChange', onSlideChange);
})

onUnmounted(() => {
  swiperInstance.value.destroy();
})


</script>
<template>
  <div class="swiper-container">
    <div class="swiper detail-swiper">
      <div class="swiper-wrapper">
        <div class="swiper-slide">Slide 1</div>
        <div class="swiper-slide">Slide 2</div>
      </div>

    </div>
    <div class="swiper-prev button-icon">
      <img src="@/assets/return.svg" alt="swiper-prev" @click="swiperInstance.slidePrev()" />
    </div>
    <div class="swiper-next button-icon">
      <img src="@/assets/go-right.svg" alt="swiper-next" @click="swiperInstance.slideNext()" />
    </div>
    <div class="custom-pagination">
      <div class="custom-pagination-bullet" :class="{ 'custom-pagination-bullet-active': swiperIndex === 0 }"></div>
      <div class="custom-pagination-bullet" :class="{ 'custom-pagination-bullet-active': swiperIndex === 1 }"></div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.swiper-container {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;

  .swiper-prev {
    position: absolute;
    left: 0;
    top: 50%;
    transform: translateY(-50%);
  }

  .swiper-next {
    position: absolute;
    right: 0;
    top: 50%;
    transform: translateY(-50%);
  }

  .button-icon {
    width: 25px;
    height: 25px;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }
}

.detail-swiper {
  // width: 100%;
  // height: 300px;
  margin-top: 16px;

  // .swiper-slide {
  //   width: 100%;
  //   height: 100%;
  //   background-color: #ccc;
  //   border-radius: 10px;
  //   box-shadow: 0 0 10px 0 rgba(0, 0, 0, 0.1);
  // }
}


.swiper {
  width: 210px;
  height: 280px;
}

.swiper-slide {
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 18px;
  font-size: 22px;
  font-weight: bold;
  color: #fff;
  border-radius: 0;
}

.swiper-slide:nth-child(1n) {
  background-color: rgb(206, 17, 17);
}

.swiper-slide:nth-child(2n) {
  background-color: rgb(0, 140, 255);
}

.custom-pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 20px;
}

.custom-pagination-bullet {
  width: 8px;
  height: 8px;
  background-color: #d9d9d9;
  border-radius: 4px;
  transition: width 0.3s ease-in-out;

  &:first-child {
    margin-right: 8px;
  }

  &-active {
    width: 24px;
  }
}
</style>
