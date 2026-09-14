<script setup lang="ts">
/**
 * 详情分析页「不健康面型」结果区。
 *
 * 仅在 `isHealthyFace === false` 时使用（DetailAnalysisView 中作为 v-else）：
 *   .analysis-failed-swiper slot 由内部三段式状态机驱动：
 *     static  → 坏面容图（点击推进）
 *     preview → 矫正后好面容图（点击推进）
 *     swiper  → 卡片 swiper（终态,含翻页 / 分页 + 诊断详情 / ScanSubscription 联动）
 *
 *   swiperIndex === 0："诊断完成" + ScanSubscription 入口
 *   swiperIndex === 1：诊断详情（title/opening/bodyPrimary）+ 护理贴士
 *
 * 与 DetailAnalysisSuccess 形成镜像（一个用户正向翻，一个反向翻，叙事方向相反）：
 *   - Success："诊断 → 订阅"（看完诊断说"引导订阅"）
 *   - Failed ："完成 → 诊断详情"（看到好面容想保存，再看诊断详情知道坏在哪）
 *
 * 父级 DetailAnalysisView 通过 @slide-change 重新转发页码给 DetailAnalysisTitle，
 * 使顶部标题随 swiper 页切换（仅在 stage === 'swiper' 时触发）。
 */
import { computed, ref } from 'vue'
import AnalysisFailedSwiper from './AnalysisFailedSwiper.vue'
import ScanSubscription from './ScanSubscription.vue'
import { DETAIL_PAGE_COPY, HABIT_COPY_MAP, type DiagnosisCopy } from '@/utils/diagnosisCopy'
import { getToothIssueImage } from '@/utils/toothIssueImages'
import { getBadHabitImages, pickRandomBadHabitImage } from '@/utils/badHabitImages'
import type { HabitCode } from '@/utils/diagnosisCopy'

interface Props {
  /** 后端返回的完整诊断结果 —— 透传给 AnalysisFailedSwiper */
  analysisResult: any
  /** 当前不健康分支对应的诊断文案（title/opening/bodyPrimary/careTips） */
  diagnosisCopy: DiagnosisCopy
  /** 矫正后的"好"面容 URL —— 作为 ScanSubscription 的订阅图,
   *  同时用于「preview」状态的图片（点击坏面容图后同位置展示矫正后面容）。
   *  标记为可选 + 默认 ''，因为 useImageSplit 的接口把 leftUrl 标成
   *  `ReturnType<typeof ref<string>>`(实际是 `Ref<string | undefined>`),
   *  未 split 完成前会是空串。与 ScanSubscription.goodImgUrl 保持同形。 */
  goodImgUrl?: string
  /** 问卷回答中触发的不良习惯编码（来自 useAnalysisStore.badHabits） */
  badHabits?: HabitCode[]
}

const props = withDefaults(defineProps<Props>(), {
  goodImgUrl: '',
  badHabits: () => [],
})

const emit = defineEmits<{
  /** 转发 AnalysisFailedSwiper 的 slideChange,
   *  让父级 DetailAnalysisView 拿到 swiperIndex,再透传给 DetailAnalysisTitle
   *  切标题文案（"专业治疗..." → "啊哦..."）。 */
  slideChange: [index: number]
  /** advance() 每次推进后通知父级,父级 DetailAnalysisView 镜像 stage 后
   *  透传给 DetailAnalysisTitle 切顶部标题文案。
   *  业务逻辑（next stage = ...）仍在本组件内部 —— 因为这是状态机本体的责任,
   *  父级只是镜像显示,不做决策。 */
  stageChange: [stage: FailedStage]
}>()

/* 本组件内 swiper 当前页 —— 唯一来源是 AnalysisFailedSwiper（其内部 ref），
   这里再镜像一份用于 v-show 切换两个文案块。 */
const swiperIndex = ref(0)

const onSwiperSlideChange = (index: number) => {
  swiperIndex.value = index
  emit('slideChange', index)
}

/**
 * 不健康分支 .analysis-failed-swiper slot 的三段式状态机。
 *
 *   static  ──点击──>  preview  ──点击──>  swiper
 *     │                  │                    │
 *   坏面容图          矫正后好面容图       卡片 swiper
 *   (默认/起手)       (过渡 / 预示)         (完整对比流程)
 *
 * 设计动机：
 *   - 「坏面容 → 好面容」对应渐进式披露：先让用户看到问题,再看矫正后的样子。
 *   - 「好面容 → swiper」是开启真正的正反面对比（swiper 同时含好/坏两张卡,
 *     配合翻页 / 分页 / divider / 诊断详情联动）。
 *   - 三段都把图放在同一 slot、同一尺寸,切换前后视觉位置稳定不跳。
 *
 * 'swiper' 是终态 —— 不会再向后推进（swiper 内部有翻页）。
 *
 * 内部状态,外部不传 —— 调用方不需要关心这个状态机的存在。
 * 若要强制重置（例如后端结果重新计算）,直接 reset 到 'static' 即可。
 *
 * 严格 3 段 —— advance() 不论 goodImgUrl 是否就绪都先进入 'preview'。
 * 如果 useImageSplit 还没完成（web/dev 环境无原生插件、或异步 split 尚未返回）,
 * preview slot 会渲染一个同尺寸的占位（详见 .failed-preview-placeholder）,
 * 用户仍然能再点击推进到 swiper,不会跳过中间的过渡态。
 */
type FailedStage = 'static' | 'preview' | 'swiper'

/**
 * 把 FailedStage 暴露给父级 DetailAnalysisView,让父级能
 * （1）作为 prop 透传给 DetailAnalysisTitle 驱动标题文案切换;
 * （2）做"外部 reset"等控制。
 *
 * 单文件内仍用 'static' | 'preview' | 'swiper' 这个 union 字符串,
 * 不引入 enum —— enum 会让 vue template 里 `:stage="Stage.Static"`
 * 这种写法必须 import,与现有 `v-if="stage === 'static'"` 风格不一致。
 */
export type { FailedStage }
const stage = ref<FailedStage>('static')

const advance = () => {
  if (stage.value === 'static') {
    stage.value = 'preview'
  } else if (stage.value === 'preview') {
    stage.value = 'swiper'
  }
  // 通知父级镜像 —— 不在 advance() 之前 emit,因为父级需要的是「最新值」
  // （不是「即将要变成的值」）,这里在赋值后再发,语义更稳。
  emit('stageChange', stage.value)
}

/**
 * 不健康分支的"坏面容"分类编码 —— 来自 `analysisResult.llmAnalysis.result.categoryCode`。
 * 缺失 / 非法值 → null（让 getToothIssueImage 走回退分支）。
 *
 * 与 AnalysisFailedSwiper 内部那份 categoryCode 是同一份数据 / 同一套解析规则；
 * 这里单独算一份是为了在 'static' / 'preview' 两态都能直接拿到 URL,
 * 不再额外给 AnalysisFailedSwiper 加 prop。
 */
const categoryCode = computed<number | null>(() => {
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
 * "坏面容"示意图 URL —— 'static' 状态渲染。
 *
 * 数据来源分两种场景：
 *   1. categoryCode > 0（存在牙颌面问题）  → getToothIssueImage(categoryCode)
 *      走「修订_牙齿问题示意图（7分类）」目录里的示意图。
 *   2. categoryCode === 0（正常面型）但问卷触发了坏习惯
 *      → 从「不良口腔习惯卡通图」目录里随机挑一张展示
 *      （一个习惯编码可能对应多张具体行为图,任选其一）。
 *
 * 同一个 `randomIndex` 不能稳定复现 —— 因为：
 *   - 同一会话内 stage 只会从 'static' 推进一次到 'preview',
 *     'static' 期间 Vue 不会重新计算（依赖未变）;
 *   - 进入 'preview' 后这张图就被替换为矫正后好面容,
 *     用户不会再次回到 'static' 看到同一张随机图;
 *   - 即便用户重置重新进入,重新随机本身也是合理的（每次诊断轮换不同提示图）。
 * 如果后续要做"同一坏习惯组合下图片稳定"的需求,可改为把随机索引放进
 * props.badHabits 衍生出的稳定 hash,这里先保持简单。
 */
const badImgUrl = computed(() => {
  if (categoryCode.value === 0 && props.badHabits.length > 0) {
    return pickRandomBadHabitImage(props.badHabits)
  }
  return getToothIssueImage(categoryCode.value)
})

/**
 * 当前坏习惯对应的全部卡通图。
 *
 * 仅在「正常面型 + 有坏习惯」场景下有内容,用于 stage === 'swiper' 时
 * 参与轮播（一张矫正后好面容 + N 张坏习惯图）。
 *
 * 非该场景返回空数组 —— AnalysisFailedSwiper 内部据此判断走哪套 slides。
 */
const habitImages = computed<string[]>(() => {
  if (categoryCode.value === 0 && props.badHabits.length > 0) {
    return getBadHabitImages(props.badHabits)
  }
  return []
})

/**
 * 实际渲染的文案。
 *
 * 当 categoryCode === 0（正常面型）且问卷回答中记录了坏习惯时，
 * 用坏习惯文案（HABIT_COPY_MAP）替代默认的正常面型文案，
 * 告知用户存在哪些不良口腔习惯及干预建议。
 *
 * 多条坏习惯时，取第一条的 title/opening/bodyPrimary，
 * 其余条目的 bodySecondary 段落合并追加到末尾。
 */
const effectiveCopy = computed<DiagnosisCopy>(() => {
  // categoryCode === 0 → 正常面型，但问卷有坏习惯 → 展示坏习惯文案
  if (categoryCode.value === 0 && props.badHabits.length > 0) {
    // 解构出的 first 静态类型是 HabitCode | undefined,即使 length>0 也无法
    // 被 TS 推导为非空 —— 用显式空检查把这条分支收缩掉,
    // 后续 HABIT_COPY_MAP[first] 才能通过索引签名校验。
    const [first, ...rest] = props.badHabits
    if (!first) return props.diagnosisCopy
    const firstCopy = HABIT_COPY_MAP[first]
    if (!firstCopy) return props.diagnosisCopy
    return {
      ...firstCopy,
      bodySecondary: [
        ...firstCopy.bodySecondary,
        ...rest.flatMap((h) => HABIT_COPY_MAP[h]?.bodySecondary ?? []),
      ],
    }
  }
  return props.diagnosisCopy
})
</script>

<template>
  <div class="analysis-failed">
    <!--
      三段式状态机驱动同一 slot 的内容：
        static  → 坏面容图      （点击推进到 preview）
        preview → 矫正后好面容图（点击推进到 swiper）
        swiper  → <AnalysisFailedSwiper>（终态）
      两张图共用同一套尺寸参数（见 .failed-bad-img / .failed-good-img）,
      保证三态切换时视觉位置稳定不跳。
    -->
    <div class="analysis-failed-swiper">
      <div v-if="stage === 'static'" class="failed-bad-img-container">
        <div class="failed-bad-img-container-click">
          <img class="failed-bad-img-container-click-img" src="@/assets/images/click-black.png" alt="click-black-img" />
          <p class="failed-bad-img-container-click-text">点一点</p>
        </div>
        <img class="failed-bad-img clickable" :src="badImgUrl"
          :alt="habitImages.length > 0 ? '不良口腔习惯示意图' : `牙颌面问题示意图-${categoryCode}`" @click="advance" />
      </div>
      <template v-else-if="stage === 'preview'">
        <!--
          goodImgUrl 尚未就绪时的占位（web/dev 环境 splitImage 不跑、
          或异步 split 还没返回）。
          同尺寸、同位置、同 cursor:pointer —— 用户可以照常点击推进,
          不会因为 src='' 而"看起来什么都没发生"。
        -->
        <div class="failed-preview-placeholder clickable" @click="advance" role="button">
          <img class="failed-preview-placeholder-img" src="@/assets/images/hands-up.png" alt="hands-up-img" />
          <div class="failed-preview-placeholder-text">
            <p class="failed-preview-placeholder-text-title">是的我可以做到</p>
            <p>【请点击此处】</p>

            <img class="failed-preview-placeholder-text-click-img" src="@/assets/images/click-white.png"
              alt="click-white-img" />
          </div>
        </div>
      </template>
      <AnalysisFailedSwiper v-show="stage === 'swiper'" :analysisResult="analysisResult" :badHabitImages="habitImages"
        @slideChange="onSwiperSlideChange" />
    </div>
    <div class="analysis-failed-divider">
      <SectionDivider>
        {{ stage === 'swiper' && swiperIndex === 0 ? '诊断完成，可以通过以下方式获取照片或结束体验！' : '诊断详情' }}
      </SectionDivider>
    </div>
    <div class="analysis-failed-content">
      <!--
        两个内容容器分时显示:
          .analysis-failed-subscription-container —— 终态 (stage === 'swiper')
            且 swiper 第 0 页 (矫正后好面容) 时显示,引导用户获取照片 /
            订阅 / 打印。
          .analysis-failed-tips-container —— 其余情况显示:static (看坏面容图)
            / preview (看好面容图) / swiper 第 1 页 (看坏面容 + 诊断详情)。
            用诊断建议 + 护理贴士 撑住内容区高度,避免空白。

        用 v-show (display:none) 而不是 v-if —— 保留两个子树在 DOM 里,
        ScanSubscription 的原生 splitImage / 打印状态 / 二维码都保持热实例,
        用户从 subscription 切到 tips 再切回 subscription 时不会有重挂载抖动。

        v-show 条件互斥且覆盖整个 stage × swiperIndex 笛卡尔积,
        任意时刻只有一个容器可见,不会同时叠加。
      -->
      <div v-show="stage === 'swiper' && swiperIndex === 0" class="analysis-failed-subscription-container">
        <ScanSubscription :good-img-url="goodImgUrl" />
      </div>
      <div v-show="stage !== 'swiper' || swiperIndex !== 0" class="analysis-failed-tips-container">
        <div class="analysis-failed-tips analysis-failed-tips--primary">
          <p v-for="(paragraph, idx) in effectiveCopy.bodyPrimary" :key="idx" class="tips-content">
            {{ paragraph }}
          </p>
        </div>
        <!--
          .analysis-failed-tips--secondary 容器托管「第二段正文 + 护理贴士」：
            - D 项(categoryCode 1~7):
                bodySecondary 是 [] → 只显示 careTips(「日常护理小贴士」+ 内容)
            - H 项(categoryCode=0 + badHabits 非空):
                careTips 是 '' → 只显示 bodySecondary(首条习惯自己的「小提醒」+
                其余条目合并追加的「小提醒」)
            bodySecondary 之前被错误地塞进 --primary 容器,H 项场景下
            --secondary 容器只能渲染空字符串 careTips,造成 display:空。
            这里按命名约定(--primary = 正文主段 / --secondary = 正文次段 + 贴士)
            把 bodySecondary 放回 --secondary 容器。
        -->
        <div class="analysis-failed-tips analysis-failed-tips--secondary" style="margin-top: 12px">
          <p v-for="(paragraph, idx) in effectiveCopy.bodySecondary" :key="'sec-' + idx" class="tips-content">
            {{ paragraph }}
          </p>
          <p v-if="effectiveCopy.careTips" class="tips-content">
            <strong>{{ DETAIL_PAGE_COPY.careTipsPrefix }}</strong>{{ effectiveCopy.careTips }}
          </p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
/*
 * 整块负责"白底圆角容器里的不健康分支"布局：
 *   - flex:1 + width:100% 接管父 .analysis-result 的剩余高度/宽度
 *   - column 布局：上 .analysis-failed-swiper（卡片图）→ 下 .analysis-failed-content（文案区）
 *   - 不能用 height:100% 因为父高度是 flex-grow 解算出来的,
 *     flex 子项百分比高度在 flex 容器下不可靠 —— 用 flex:1 替代。
 */
.analysis-failed {
  flex: 1;
  width: 100%;
  display: flex;
  flex-direction: column;
  background: transparent;
  padding-top: 70px;

  /* swiper 容器 —— 让 AnalysisFailedSwiper 撑满父级剩余高度：
   *   - flex:1 + min-height:0 吃掉 .analysis-failed 减去 .analysis-failed-content 后的空间
   *   - min-height:0 是必须的：flex item 默认 min-height:auto,会拒绝收缩到内容固有高度以下,
   *     导致 swiper 在小屏里把 .analysis-failed-content 挤出可视区
   *   - width:100% + display:flex/column 让内部的 AnalysisFailedSwiper
   *     (.swiper-container height:100%) 拿到确定的容器高度 */
  .analysis-failed-swiper {
    height: 700px;
    width: 100%;
    display: flex;
    flex-direction: column;
    padding: 0 40px;

    .failed-bad-img-container {
      position: relative;
      margin: 16px auto 0;
      max-width: 512px;

      .failed-bad-img-container-click {
        position: absolute;
        right: -60px;
        top: 50%;
        display: flex;
        flex-direction: column;
        font-size: 32px;
        color: #000;
        font-weight: 700;
        align-items: center;

        .failed-bad-img-container-click-img {
          width: 135px;
          height: 135px;
        }

        .failed-bad-img-container-click-text {
          padding-left: 42px;
          font-weight: 700;
        }
      }
    }

    /**
     * 「static」+「preview」两态的图共用这一套尺寸参数：
     *   - aspect-ratio: 3/4 + max-width: 512px + width: 100%
     *   - margin: 16px auto 0 让图在 flex column(.analysis-failed-swiper) 中水平居中
     *   - border-radius: 40px 与 AnalysisFailedSwiper 内的 .swiper 卡片同形
     *
     * 与 .analysis-failed-swiper 的 flex column 容器配合,
     * 保证 static→preview→swiper 切换时图的位置 / 圆角稳定不跳
     * （进入 swiper 后是 swiper 内部 .swiper 卡片自己定位）。
     */
    .failed-bad-img {
      display: block;
      width: 100%;

      aspect-ratio: 3 / 4;
      object-fit: contain;
      border-radius: 40px;
    }

    .failed-good-img {
      display: block;
      width: 100%;
      max-width: 512px;
      margin: 16px auto 0;
      aspect-ratio: 3 / 4;
      object-fit: contain;
      border-radius: 40px;
    }

    /**
     * 「preview」状态下 goodImgUrl 尚未就绪时的占位。
     *
     * 与 .failed-bad-img / .failed-good-img 共用同一套尺寸参数,
     * 保证从 static→preview→swiper 切换时位置稳定不跳。
     *
     * flex + 居中让"加载中"文案位于占位正中央;
     * 用浅灰渐变背景 + 圆角,视觉上和真正的"图"卡片相近,
     * 不让用户觉得这一格突然变成了一个 div。
     */
    .failed-preview-placeholder {
      width: 100%;
      max-width: 512px;
      margin: 16px auto 0;
      aspect-ratio: 3 / 4;
      border-radius: 40px;
      background: #FF9900;
      display: flex;
      justify-content: center;
      padding-top: 20%;

      .failed-preview-placeholder-img {
        width: 68px;
        height: 68px;
        margin-top: 8px;
      }
    }

    .failed-preview-placeholder-text {
      font-size: 20px;
      color: #fff;
      letter-spacing: 1px;
      margin-left: 20px;

      &-title {
        font-weight: 700;
        font-size: 32px;
      }

      &-click-img {
        width: 83px;
        height: 83px;
        margin-left: 20px;
      }
    }

    /**
     * 「static」/「preview」两态的图都是可点击的 —— 用 .clickable
     * 标记给 cursor + hover/active 反馈。
     *
     * hover 时轻微下沉 + 阴影,提示用户这是可交互元素;
     * active 时再缩放 + 不透明,模拟按压反馈。
     * 进入「swiper」态后该 class 被卸载,转用 swiper 自己的翻页 / 分页交互。
     */
    .clickable {
      cursor: pointer;
      transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;

      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
      }

      &:active {
        transform: scale(0.98);
        opacity: 0.88;
      }
    }
  }

  .analysis-failed-divider {
    width: 100%;
    padding: 0 80px;
    margin-top: 20px;
  }

  /* 文案区：swiper 下方 36px 留白,水平居中。
     ScanSubscription / analysis-failed-tips 都是 width:100% 的块,
     用 flex + justify-content:center 让它们在外层 flex box 里水平居中。

     高度契约 —— 把盒子高度锁死在 180px,防止内容溢出撑高页面:
       - 默认 .analysis-failed-content 的 flex 是 `0 1 auto`，
         flex-shrink:1 在父 .analysis-failed(也是 flex column)空间紧张时
         会让本块被压缩。即便 CSS 规范里 min-height 应作为 flex 的地板,
         实际在多层嵌套 flex + overflow:auto/hidden 组合下,
         部分浏览器(Webkit 系尤其)仍会按 flex-shrink 把盒子压到 min-height 之下。
       - 显式 flex-shrink:0 锁住,让本块在 flex 分发里只能 grow 或保持,
         不会被父级链上的 shrink 推下来。
       - align-self:stretch 是 column flex 的默认值(填满交叉轴 = 横向宽度),
         这里显式写出来,和 DetailAnalysisSuccess 的 .analysis-success-swiper
         对齐写法,避免有人后续把父 align-items 改掉后这里跟着跑偏。

     高度锁死 —— 同时设 min/max-height:180px,加 overflow-y:auto:
       - 内容 < 180px：min-height 撑到 180px,盒内下方留白(与原设计一致)
       - 内容 > 180px：max-height 截到 180px,溢出内容在盒内出滚动条,
         而不是继续向下溢出撑高整个页面 / 触发出现在 .detail-analysis-page 上的
         整页滚动条。
       - overflow-x 保持默认 visible,不要被滚动条裁掉横向 padding。
       - scrollbar-gutter:stable 给滚动条预留稳定槽位,避免出现/消失滚动条
         时盒内左右内容轻微跳动(Chrome 94+ / Safari 16+ 才支持,但本项目
         运行在 Capacitor H5 / iOS WebKit 上,版本均已满足)。 */
  .analysis-failed-content {
    margin-top: 36px;
    display: flex;
    justify-content: center;
    align-self: stretch;
    flex-shrink: 0;
    min-height: 350px;
    max-height: 350px;
    overflow-y: auto;
    scrollbar-gutter: stable;

    // 滚动条定制 —— 默认 webkit 滚动条 16px 宽,会挤压左右内边距;
    // 这里缩到 6px 半透明,视觉上不抢戏。
    &::-webkit-scrollbar {
      width: 6px;
    }

    &::-webkit-scrollbar-thumb {
      background: rgba(0, 0, 0, 0.18);
      border-radius: 3px;
    }

    &::-webkit-scrollbar-track {
      background: transparent;
    }

    // Firefox 走标准 scrollbar-width / scrollbar-color
    scrollbar-width: thin;
    scrollbar-color: rgba(0, 0, 0, 0.18) transparent;


    .analysis-failed-subscription-container {
      /* 与 .analysis-failed-tips-container 横向 padding 一致,
         保证两套内容容器切换时左右边界不跳。
         ScanSubscription 自身 width:100%,不再额外留 margin */
      padding: 0 90px;
    }

    .analysis-failed-tips-container {
      padding: 0 90px;
    }
  }

  /* 黄色 tips 块（诊断详情） —— 与 DetailAnalysisSuccess 的 .analysis-success-tips
     同色（#FFE361），但宽度顶满父级（不留左右内边距），样式略有差异：
       居中文案 + 左侧对齐的块 + 额外的 decorative 装饰 */
  .analysis-failed-tips {
    font-family:
      'Inter',
      -apple-system,
      BlinkMacSystemFont,
      sans-serif;
    font-size: 20px;
    font-weight: 400;
    color: #000;
    text-align: center;
    width: 100%;
    background-color: #BFF1FF;
    box-sizing: border-box;
    padding: 32px 28px;
    border-radius: 35px;
    text-align: left;
    position: relative;

    .tips-content {
      font-size: 20px;
      line-height: 24px;
      margin-bottom: 12px;

      &:last-child {
        margin-bottom: 0;
      }
    }
  }

  /* swiper 页 0 顶部引导句（"诊断完成..."）的字号比 tips 大 */
  .failed-content-handle-img-tips {
    font-size: 30px;
    line-height: 36px;
    font-weight: 400;
    margin-bottom: 24px;
  }

  /* 橙色 tips 块（护理贴士）—— 比 primary 块更浅的橙色,
     通过装饰图 + 居中绝对定位浮在块顶部。 */
  .analysis-failed-tips--secondary {

    img {
      width: 80px;
      position: absolute;
      left: 50%;
      transform: translateX(-50%);
      top: -40px;
    }
  }
}
</style>
