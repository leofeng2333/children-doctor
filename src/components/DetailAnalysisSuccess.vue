<script setup lang="ts">
/**
 * 详情分析页「健康面型」结果区。
 *
 * 仅在 `isHealthyFace === true` 时使用：
 *   - 大图（矫正后好面容，与 ScanSubscription 复用）
 *   - 健康引导句 + 护理贴士（来自 `getDiagnosisCopy(0)`）
 *   - 诊断正文（bodyPrimary）+ ScanSubscription 入口 → 两个放进 swiper 左右翻页
 */
import Swiper from 'swiper'
import 'swiper/css'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import ScanSubscription from '@/components/ScanSubscription.vue'
import type { DiagnosisCopy } from '@/utils/diagnosisCopy'
import SectionDivider from './SectionDivider.vue'

interface Props {
  /** 健康面型诊断文案（opening / careTips / bodyPrimary）。 */
  copy: DiagnosisCopy
  /** 健康面容图 URL —— 同时作为 ScanSubscription 订阅图。 */
  imgUrl: string
}

defineProps<Props>()

/* ===== swiper 状态：实例句柄 + 当前页（用于 divider 文案联动） ===== */
const swiperInstance = ref<any>(null)
const swiperIndex = ref(0)

/* divider 文案随页码切换：
   - 第 0 页（诊断正文）→ "诊断建议"
   - 第 1 页（ScanSubscription 入口）→ "诊断完成，可以通过以下方式获取照片或结束体验！" */
const dividerText = computed(() =>
  swiperIndex.value === 0 ? '诊断建议' : '诊断完成，可以通过以下方式获取照片或结束体验！',
)

const onSlideChange = (e: any) => {
  swiperIndex.value = e.activeIndex
}

onMounted(() => {
  swiperInstance.value = new Swiper('.detail-success-swiper', {
    // 关闭 autoHeight —— 让 swiper 高度由父级 .analysis-success-swiper 的
    // flex:1 决定,而不是由"两条 slide 中最高的那条"决定。
    // 这样两条 slide 才能各自 height:100% 填满 swiper,
    // 进而让 .analysis-success-body height:100% 真正继承到 swiper 高度。
    autoHeight: false,
  })
  swiperInstance.value.on('slideChange', onSlideChange)
})

onUnmounted(() => {
  swiperInstance.value?.destroy()
  swiperInstance.value = null
})
</script>

<template>
  <div class="analysis-success">
    <div class="analysis-success-img">
      <div class="analysis-success-tips">
        <img src="@/assets/images/analysis-success-tips.png" alt="analysis-success-tips-img" />
        <h3 class="tips-title">{{ copy.opening }}</h3>
        <p class="tips-content">{{ copy.careTips }}</p>
      </div>
      <img :src="imgUrl" alt="analysis-success" />
    </div>
    <!--
      divider-wrapper 仅承担"给 SectionDivider 加左右 90px 边距"的职责。
      SectionDivider 自身 width:100%,不能直接挂 margin(会撑出父级),
      所以这里包一层(详见 .divider-wrapper 注释)。
    -->
    <div class="divider-wrapper">
      <SectionDivider>
        {{ dividerText }}
      </SectionDivider>
    </div>

    <!--
      轮播区（按 AnalysisFailedSwiper 的结构搭）：
        .analysis-success-swiper（外层） —— 仅作为定位锚点 + 容纳 prev/next
          .swiper.detail-success-swiper  —— 真正的 swiper 容器（带 overflow:hidden）
            .swiper-wrapper
              .swiper-slide × 2
          .swiper-prev / .swiper-next —— 必须是 .swiper 的兄弟，不能塞进 .swiper 里
            （否则被 .swiper 的 overflow:hidden 裁掉，按钮看不见、点不到）
      初始化 selector 指向 .detail-success-swiper（与 .detail-swiper 同源写法）。
    -->
    <div class="analysis-success-swiper">
      <div class="swiper detail-success-swiper">
        <div class="swiper-wrapper">
          <div class="swiper-slide">
            <div class="analysis-success-body">
              <div class="analysis-success-body-content">
                <p v-for="(paragraph, idx) in copy.bodyPrimary" :key="idx" class="tips-content">
                  {{ paragraph }}
                </p>
              </div>
            </div>
          </div>
          <div class="swiper-slide">
            <ScanSubscription :good-img-url="imgUrl" />
          </div>
        </div>
      </div>

      <div class="swiper-prev button-icon tappable">
        <img src="@/assets/return.svg" alt="swiper-prev" @click="swiperInstance?.slidePrev()" />
      </div>
      <div class="swiper-next button-icon tappable">
        <img src="@/assets/go-right.svg" alt="swiper-next" @click="swiperInstance?.slideNext()" />
        <!--
          「点一点」手势图:塞进 swiper-next 内部,绝对定位于 swiper-next
          左侧 -110px(top:50% 垂直居中),跟按钮共用同一个定位上下文,
          不再依赖 .analysis-success-swiper 的宽度 —— 无论外层容器宽
          多少,手势图始终紧贴翻页按钮左侧(间距 10px),不会跑到别的位
          置。pointer-events:none 不抢按钮的 click;z-index:11 > .swiper-next
          (10) 保证渲染在按钮之上不被遮。
        -->
        <img class="click-black" src="@/assets/images/click-black.png" alt="click-black-img" />
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.analysis-success {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  /* 内边距已全部下放到三个直接子元素（img / divider-wrapper / swiper-wrapper）：
       - 70px 顶部 → .analysis-success-img 的 margin-top
       - 90px 左右 → 三个子元素各自的 margin-left/right
     父级不再持有任何内边距，仅作布局容器 */

  .analysis-success-img {
    width: 100%;
    max-width: 542px;
    aspect-ratio: 3 / 4;
    /* 接管原父级 padding:
         - 70 顶部 = 不要顶到白色卡片圆角边
         - 90 左右 = 不要贴到卡片左右边
         - 90 底部 = 维持原 margin-bottom,与 SectionDivider 隔开
       box-sizing 让 width:100% 在加 margin 后不撑出父级 */
    margin: 70px 90px 90px;
    box-sizing: border-box;
    border-radius: 40px;
    // overflow: hidden;
    position: relative;

    img {
      display: block;
      width: 100%;
      height: 100%;
      max-width: 100%;
      max-height: 100%;
      object-fit: contain;
    }
  }

  /* SectionDivider 的边距承接层 —— SectionDivider 自身 width:100% 不能直接加 margin
     （会撑出父级），所以这里包一层让它能挂左右边距 */
  .divider-wrapper {
    /* 不要写 width:100% —— 会和 margin:0 90px 一起把盒子撑出父级 X+180px
       （CSS Flexbox §6.1：align-self:stretch 要求 width:auto 才生效,
       显式 width:100% 会让 stretch 失效）
       这里用 align-self:stretch 让 flex 自动算 "父宽 - 左右 margin" 的可用空间 */
    align-self: stretch;
    margin: 0 90px;
  }

  /* 轮播外层容器 —— 定位锚点，prev/next / pagination 都以它定位。
     与 AnalysisFailedSwiper 的 .swiper-container 同等角色。
     接管原父级 padding 的左右 90px。 */
  .analysis-success-swiper {
    position: relative;
    /* 不要写 width:100% —— 显式 width 会让 align-self:stretch 失效,
       结果是 width:100% + margin:0 90px 把盒子撑成 父级 X + 180px,横向溢出。
       去掉 width 让 stretch 接管,flex 算出的可用宽度 = X - 180px,
       外缘恰好贴父级边缘。 */
    align-self: stretch;
    margin: 0 36px;
    min-height: 180px;
    margin-top: 36px;
  }

  /* 真正的 swiper 元素（同时挂 .swiper 与 .detail-success-swiper） */
  .detail-success-swiper {
    width: 700px;
    height: 100%;
    /* autoHeight:true 时 swiper 会按当前 slide 自适应高度，无需 aspect-ratio 锁定 */
  }

  .swiper-prev {
    position: absolute;
    left: 0;
    top: 50%;
    transform: translateY(-50%);
    z-index: 10;
  }

  .swiper-next {
    position: absolute;
    right: 0;
    top: 50%;
    transform: translateY(-50%);
    z-index: 10;
  }

  /* 「点一点」手势图 —— 塞进 .swiper-next 内部,绝对定位 left:-110px 顶
     在按钮左侧 10px(top:50% + translateY(-50%) 垂直居中)。跟按钮共用
     同一个定位上下文,不依赖外层容器宽度,稳贴 swiper-next。
     pointer-events:none 不抢按钮的 click;z-index:11 > .swiper-next(10)。 */
  .swiper-next .click-black {
    position: absolute;
    left: -110px;
    top: 50%;
    transform: translateY(-50%);
    width: 100px;
    height: 100px;
    pointer-events: none;
    z-index: 11;
  }

  .button-icon {
    width: 40px;
    height: 40px;
    cursor: pointer;

    img {
      width: 100%;
      height: 100%;
      object-fit: cover;
    }
  }

  .analysis-success-body {
    font-family:
      'Inter',
      -apple-system,
      BlinkMacSystemFont,
      sans-serif;
    font-size: 24px;
    line-height: 30px;
    font-weight: 400;
    height: 100%;
    display: flex;
    flex-direction: column;
    justify-content: center;
    align-items: center;

    .analysis-success-body-content {
      width: 100%;
      background-color: #BFF1FF;
      border-radius: 35px;
      padding: 24px;
    }
  }

  .handle-img-tips {
    width: 100%;
    text-align: left;
    font-size: 24px;
    line-height: 36px;
    font-weight: 400;
    margin-bottom: 24px;
  }

  .analysis-success-tips {
    font-family:
      'Inter',
      -apple-system,
      BlinkMacSystemFont,
      sans-serif;
    font-size: 16px;
    font-weight: 400;
    color: #000;
    position: absolute;
    bottom: -8%;
    right: -10%;
    text-align: center;
    width: 270px;
    background-color: #FFE361;
    box-sizing: border-box;
    padding: 20px 12px;
    text-align: left;

    .tips-title {
      font-size: 20px;
      // zoom: 0.65;
      line-height: 1;
      font-weight: 700;
      margin-top: 4px;
      margin-bottom: 12px;
    }

    .tips-content {
      font-size: 20px;
      // zoom: 0.65;
      line-height: 24px;
    }

    img {
      width: 80px;
      position: absolute;
      left: 100px;
      top: -72px;
    }
  }
}
</style>
