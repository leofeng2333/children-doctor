<script setup lang="ts">
import Swiper from 'swiper'
import 'swiper/css'
import 'swiper/css/effect-cards'

// import './style.css';

// import required modules
import { EffectCards } from 'swiper/modules'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useImageSplit } from '@/composables/useImageSplit'
import { getToothIssueImage } from '@/utils/toothIssueImages'
import { getDiagnosisCopyFromLLMResult } from '@/utils/diagnosisCopy'

const emit = defineEmits(['slideChange'])

interface Props {
  analysisResult: any
}

const props = defineProps<Props>()

const imgSrc = computed(
  () => props.analysisResult?.aiAnalysis?.result?.predictions?.futureImageUrl ?? '',
)

/**
 * 矫正后的"好"面容（swiper 第 1 张）。
 *
 * useImageSplit 内部已交叉赋值：返回的 leftUrl 实为原图右半（好图）。
 */
const { leftUrl: goodImgUrl } = useImageSplit(
  () => imgSrc.value,
  0.5,
  { inset: 20 },
)

/**
 * 不健康分支当前的诊断分类编码（来自 `llmAnalysis.result.categoryCode`）。
 * 非法/缺失值 → 走 getToothIssueImage 的回退分支。
 */
const categoryCode = computed(() => {
  const llmResult = props.analysisResult?.llmAnalysis?.result
  if (!llmResult || typeof llmResult !== 'object') return null
  const raw = (llmResult as Record<string, any>).categoryCode
  if (typeof raw === 'number' && Number.isInteger(raw)) return raw
  if (typeof raw === 'string') {
    const n = Number(raw)
    if (Number.isInteger(n)) return n
  }
  return null
})

/**
 * 不健康面型的示意图（swiper 第 1 张）：
 * 按当前分类编码返回对应牙颌面问题示意图；
 * 不再依赖 useImageSplit 切出的左半坏脸。
 */
const badIssueImageUrl = computed(() => getToothIssueImage(categoryCode.value))

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
          <img :src="goodImgUrl" alt="矫正后面容" srcset="" />
        </div>
        <div class="swiper-slide">
          <img :src="badIssueImageUrl" :alt="`牙颌面问题示意图-${categoryCode}`" srcset="" />
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

/**
 * swiper effect-cards 会在下一张卡片上插入 .swiper-slide-shadow 系列元素
 * 用于模拟层叠卡片阴影。去除背景色，保留阴影本身的视觉层次。
 *
 * 注：swiper 在运行时注入这些 class（不在模板静态出现），
 * 必须用 :deep() 穿透 scoped 才能命中。
 */
:deep(.swiper-slide-shadow),
:deep(.swiper-slide-shadow-cards),
:deep(.swiper-slide-shadow-coverflow) {
  background: transparent !important;
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
  background-color: #FF9900;
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
