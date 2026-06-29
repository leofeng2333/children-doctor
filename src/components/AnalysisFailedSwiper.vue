<script setup lang="ts">
import Swiper from 'swiper'
import 'swiper/css'
import 'swiper/css/effect-cards'

// import './style.css';

// import required modules
import { EffectCards } from 'swiper/modules'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useImageSplit } from '@/composables/useImageSplit'

const emit = defineEmits(['slideChange'])

interface Props {
  analysisResult: any
}

const props = defineProps<Props>()

const imgSrc = computed(() => props.analysisResult?.aiAnalysis.result.generatedImageUrls[0])

const { leftUrl: leftUrlRef, rightUrl: rightUrlRef } = useImageSplit(() => imgSrc.value, 0.5)

const leftUrl = computed(() => leftUrlRef.value)
const rightUrl = computed(() => rightUrlRef.value)

const swiperInstance = ref<any>(null)

const swiperIndex = ref(0)

const onSlideChange = (e: any) => {
  swiperIndex.value = e.activeIndex
  emit('slideChange', e.activeIndex)
}

onMounted(() => {
  swiperInstance.value = new Swiper('.detail-swiper', {
    effect: 'cards',
    grabCursor: true,
    modules: [EffectCards],
  })

  swiperInstance.value.on('slideChange', onSlideChange)
})

onUnmounted(() => {
  swiperInstance.value.destroy()
})
</script>
<template>
  <div class="swiper-container">
    <div class="swiper detail-swiper">
      <div class="swiper-wrapper">
        <div class="swiper-slide">
          <img :src="leftUrl" alt="bad-img" srcset="" />
        </div>
        <div class="swiper-slide">
          <img :src="rightUrl" alt="good-img" srcset="" />
        </div>
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
    width: 60px;
    height: 60px;

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
  width: 100%;
  max-width: 485px;
  height: auto;
  aspect-ratio: 3 / 4;
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
  background-color: transparent;

  img {
    display: block;
    max-width: 100%;
    max-height: 100%;
    width: auto;
    height: auto;
    object-fit: contain;
  }
}

.custom-pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  margin-top: 32px;
}

.custom-pagination-bullet {
  width: 16px;
  height: 16px;
  background-color: #d9d9d9;
  border-radius: 8px;
  transition: width 0.3s ease-in-out;

  &:first-child {
    margin-right: 16px;
  }

  &-active {
    width: 48px;
  }
}
</style>
