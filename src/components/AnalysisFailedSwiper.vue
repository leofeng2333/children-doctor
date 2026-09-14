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
  /**
   * 不良口腔习惯对应的全部卡通图（来自 src/utils/badHabitImages）。
   *
   * 由 DetailAnalysisFailed 计算后传入:
   *   - 有值（长度 >= 1）: 正常面型 + 问卷触发坏习惯的场景,
   *     swiper 在矫正后好面容之后,依次展示这些坏习惯图参与轮播。
   *   - 空数组 / 未传: 走原有逻辑,swiper 第 1 张显示
   *     getToothIssueImage(categoryCode) 的牙颌面问题示意图。
   *
   * 与 swiper 现有的「矫正后好面容 / 牙颌面问题图」二选一关系 —— 不会同时出现,
   * 由 DetailAnalysisFailed 在 categoryCode === 0 && badHabits.length > 0 时
   * 决定走哪一支。
   */
  badHabitImages?: string[]
}

const props = withDefaults(defineProps<Props>(), {
  badHabitImages: () => [],
})

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
 * 不健康面型的示意图（swiper 在「坏习惯」场景下不使用,「牙齿问题」场景下的第 1 张）。
 *
 * 按当前分类编码返回对应牙颌面问题示意图；
 * 不再依赖 useImageSplit 切出的左半坏脸。
 */
const badIssueImageUrl = computed(() => getToothIssueImage(categoryCode.value))

/**
 * swiper 第 1 张及之后要展示的「坏习惯/问题图」列表。
 *
 *   - 坏习惯场景（badHabitImages.length > 0）: 返回传入的图数组,每张轮播一张。
 *   - 牙齿问题场景（badHabitImages.length === 0）: 返回 [badIssueImageUrl],
 *     保留原有「好面容 / 牙颌面问题图」二段式结构。
 *
 * 让模板只用一份 v-for,不再写两份 swiper-slide 块,坏习惯 / 牙齿问题
 * 两套场景共用同一份 swiper 容器,减少 DOM 分叉。
 */
const tailImages = computed<string[]>(() => {
  if (props.badHabitImages.length > 0) return props.badHabitImages
  return badIssueImageUrl.value ? [badIssueImageUrl.value] : []
})

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
    modules: [],
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
        <!--
          slide 0 始终是矫正后好面容（goodImgUrl）;
          slide 1~N 由 tailImages 决定：
            - 坏习惯场景: 每张卡通图各占一张,用户可轮播查看
            - 牙齿问题场景: 单张牙齿问题示意图
          父级 DetailAnalysisFailed 通过 swiperIndex 控制：
            - index === 0 → 「矫正后面容 + 引导订阅」
            - index >= 1  → 「诊断详情」,正好对应 tailImages 中任意一张
        -->
        <div class="swiper-slide">
          <img :src="goodImgUrl" alt="矫正后面容" srcset="" />
        </div>
        <div v-for="(img, idx) in tailImages" :key="`tail-${idx}`" class="swiper-slide">
          <img :src="img"
            :alt="badHabitImages.length > 0 ? `不良口腔习惯示意图-${idx + 1}` : `牙颌面问题示意图-${categoryCode}`"
            srcset="" />
        </div>
      </div>
    </div>
    <div class="swiper-prev button-icon tappable">
      <img src="@/assets/return.svg" alt="swiper-prev" @click="swiperInstance.slidePrev()" />
    </div>
    <div class="swiper-next button-icon tappable">
      <img src="@/assets/go-right.svg" alt="swiper-next" @click="swiperInstance.slideNext()" />
    </div>
    <!-- <div class="custom-pagination">
      <div class="custom-pagination-bullet" :class="{ 'custom-pagination-bullet-active': swiperIndex === 0 }"></div>
      <div class="custom-pagination-bullet" :class="{ 'custom-pagination-bullet-active': swiperIndex === 1 }"></div>
    </div> -->
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
    width: 40px;
    height: 40px;

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
