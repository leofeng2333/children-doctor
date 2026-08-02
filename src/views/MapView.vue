<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'
import { Toast } from '@capacitor/toast'
import { useUserStore } from '@/stores'
import { saveUserInfo } from '@/utils/service'

const router = useRouter()

const chartRef = ref<HTMLDivElement | null>(null)

const echartsInstance = ref<echarts.ECharts | null>(null)
const currentMapName = ref('浙江省')

const locationList = ref<any[]>([])

const cityMap: Record<
  string,
  { key: string; name: string; center: [number, number]; zoom: number }
> = {
  杭州市: { key: '330100', name: '杭州市', center: [119.5, 29.8], zoom: 1.2 },
  绍兴市: { key: '330600', name: '绍兴市', center: [120.6, 29.8], zoom: 1.1 },
  金华市: { key: '330700', name: '金华市', center: [120.0, 29.1], zoom: 1.1 },
  义乌市: { key: '330782', name: '义乌市', center: [120.05, 29.3], zoom: 1.1 },
}

const btnLoading = ref(false)
const goNext = async () => {
  if (!locationList.value.length || cityMap[locationList.value.lastItem.name]) {
    Toast.show({
      text: '请选择县市区',
      position: 'center',
    })
    return
  }
  const { nickname, phone } = useUserStore()
  const params: Record<string, string> = {
    nickname: nickname,
    phone: phone,
    province: '浙江省',
  }
  // 业务规则：若选择路径包含义乌市，则把它视为地级市，
  // 其子级（街道/镇）作为 district；路径中更早的节点（金华市）忽略。
  const yiwuIdx = locationList.value.findIndex(
    (item: any) => item?.name === '义乌市',
  )
  if (yiwuIdx >= 0) {
    params.city = '义乌市'
    params.district = locationList.value[yiwuIdx + 1]?.name
  } else {
    params.city = locationList.value[0].name
    params.district = locationList.value[1]?.name
  }
  btnLoading.value = true
  const tempResponse = await saveUserInfo(params).finally(() => {
    btnLoading.value = false
  })
  console.log('tempResponse', tempResponse)
  router.push('/diagnosis')
}

const showReturn = computed(() => {
  return currentMapName.value !== '浙江省'
})

// 给每个地图层级一组手工调色板, 让相邻城市颜色区分又不刺眼.
// 颜色以品牌色 (黄/橙红) 为主, 蓝绿点缀, 保证同一地图内不撞色.
// 调色板按子区域顺序循环, 颜色索引由 features 顺序稳定决定 (不依赖 fetch 顺序).
const mapPalette: Record<string, string[]> = {
  浙江省: ['#FFE361', '#FFD580', '#FFB870', '#FFA060', '#FF8A55', '#FFB3B3', '#E8C5FF', '#C5E8FF', '#B8E8D0', '#F7C8B0', '#F0E0A8'],
  杭州市: ['#FFE361', '#FFCD7A', '#FFB870', '#FFA060', '#FFD580', '#F0E0A8', '#FF8A55', '#C5E8FF'],
  绍兴市: ['#FFE361', '#FFB870', '#FFA060', '#FFD580', '#FFCD7A', '#B8E8D0', '#F0E0A8'],
  金华市: ['#FFE361', '#FFB870', '#FFA060', '#FFCD7A', '#FFD580', '#E8C5FF', '#F0E0A8'],
  义乌市: ['#FFE361', '#FFB870', '#FFA060', '#FFCD7A', '#FFD580', '#B8E8D0'],
}

const handleReturn = () => {
  if (locationList.value.length === 3 || locationList.value.lastItem.name === '义乌市') {
    locationList.value.length = 1
    currentMapName.value = '金华市'
    const cityInfo = cityMap['金华市']!
    initMap(cityInfo.key, cityInfo.name, cityInfo.center, cityInfo.zoom)
  } else {
    locationList.value = []
    currentMapName.value = '浙江省'
    initMap('330000', '浙江省', [120.4, 29.1], 1.1)
  }
}

const initMap = async (mapKey: string, mapName: string, center: [number, number], zoom: number) => {
  if (!chartRef.value) return
  const url = `/${mapKey}.json`
  const response = await fetch(url)
  const geoJson = await response.json()
  console.log('geoJson', geoJson)

  // 同一容器每次重建实例, 避免切换地图后 series 缓存导致颜色错乱 / 全黑.
  // (ECharts map series 的 itemStyle.areaColor 不支持回调, 必须在 data 上指定)
  if (echartsInstance.value) {
    echartsInstance.value.dispose()
    echartsInstance.value = null
  }
  const chart = echarts.init(chartRef.value)
  echartsInstance.value = chart
  echarts.registerMap(mapName, geoJson)

  const palette = mapPalette[mapName] ?? ['#E8E8E8']

  // 从 geoJson.features 读出每个区域的名字, 生成 ECharts map series 需要的 data 数组.
  // 这是 ECharts 唯一支持的"每个区域独立 areaColor"的方式.
  // 颜色按 features 顺序循环调色板, 保证相邻城市颜色不同 (哈希方案在 "XX市" 这种
  // 短汉字串上散列差, 11 个省辖市会撞到同一个色).
  const features: any[] = geoJson.features ?? []
  const data = features.map((f: any, i: number) => {
    const name: string = f.properties?.name ?? ''
    return {
      name,
      itemStyle: {
        areaColor: palette[i % palette.length] ?? '#E8E8E8',
        borderColor: '#FFFFFF',
        borderWidth: 1.5,
      },
    }
  })

  const option = {
    backgroundColor: '#FFFFFF',
    tooltip: {
      trigger: 'item',
      show: false,
    },
    series: [
      {
        name: mapName,
        type: 'map',
        map: mapName,
        data,
        roam: true,
        scaleLimit: {
          min: 0.8,
          max: 3,
        },
        zoom,
        center,
        label: {
          show: true,
          fontSize: 10,
          color: '#333',
        },
        itemStyle: {
          // series-level 兜底色, 没匹配到的区域用这个.
          areaColor: '#E8E8E8',
          borderColor: '#FFFFFF',
          borderWidth: 1.5,
        },
        emphasis: {
          label: {
            show: true,
          },
          itemStyle: {
            // hover 时统一高亮品牌橙.
            areaColor: '#FF9900',
            borderColor: '#FF9900',
          },
        },
        select: {
          disabled: true,
        },
      },
    ],
  }

  // notMerge: true 强制丢弃旧 option, 否则 series.data 没匹配到上次数据时会残留默认色.
  chart.setOption(option, { notMerge: true })

  // 每次重建实例都要重新绑定 click. 之前的实现把 click 绑在 onMounted 里,
  // 只绑了第一次的实例, 第二次 initMap dispose 旧实例后 click 就丢了.
  chart.on('click', handleMapClick)
}

function handleMapClick(params: any) {
  console.log('city', params)

  const cityInfo = cityMap[params.name]

  if (!locationList.value.length) {
    locationList.value.push(params)
  }

  if (params.seriesName === '浙江省') {
    locationList.value = [params]
  }

  if (cityInfo) {
    currentMapName.value = cityInfo.name
    initMap(cityInfo.key, cityInfo.name, cityInfo.center, cityInfo.zoom)
  }

  while (locationList.value.length && locationList.value.lastItem.name !== params.seriesName) {
    locationList.value.pop()
  }
  locationList.value.push(params)
}

onMounted(async () => {
  await initMap('330000', '浙江省', [120.4, 29.1], 1.1)
})

onUnmounted(() => {
  echartsInstance.value?.dispose()
  echartsInstance.value = null
})
</script>

<template>
  <div class="form-container">
    <div class="page-top-container">
      <div class="welcome-text">Hi，<br />我是你的AI口腔医生！</div>

      <h1 class="form-title">我们先来填写用户的问诊单吧。</h1>

      <div class="page-content">
        <h2 class="map-title">你现居住在哪里？</h2>

        <div class="position-list">
          <div class="position-item">浙江省</div>
          <template v-for="item in locationList">
            <span class="position-item-separator"></span>
            <div class="position-item">{{ item.name }}</div>
          </template>
        </div>

        <div class="map-area">
          <img v-show="showReturn" src="@/assets/return.svg" alt="返回" class="return-icon" @click="handleReturn" />
          <div ref="chartRef" class="map-container"></div>
        </div>
      </div>
    </div>

    <div class="bottom-section-buttons">
      <PrimaryButton text="下一步" :loading="btnLoading" @click="goNext" />
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped>
.form-container {
  height: 100vh;

  @supports (height: 100dvh) {
    height: 100dvh;
  }

  background: #ffffff;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(42px + env(safe-area-inset-bottom));
  overflow: hidden;
  justify-content: space-between;
}

.welcome-text {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
}

.form-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 16px 0 30px 0;
}

.map-title {
  font-family:
    'Inter',
    -apple-system,
    BlinkMacSystemFont,
    sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin-bottom: 12px;
}

.page-content {
  flex-grow: 1;
  flex-shrink: 1;
  flex-basis: 0%;

  .position-list {
    display: flex;
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
  }

  .position-item {
    padding: 12px 26px;
    color: #000;
    font-size: 24px;
    background: #FFE361;
    border-radius: 50px;
    line-height: 1;
    box-sizing: border-box;
    min-height: 32px;
    min-width: 48px;
  }

  .position-item-separator {
    width: 12px;
    height: 1px;
    background-color: #000;
    margin: 0 12px;
    color: #000;
  }

  .map-area {
    display: flex;
    flex-direction: column;
    margin-top: 16px;
    position: relative;

    .return-icon {
      position: absolute;
      z-index: 10;
      top: 42px;
      left: 0;
      height: 32px;
    }

    .map-container {
      width: 100%;
      height: 600px;
    }
  }
}

.bottom-section-buttons {
  display: flex;
  flex-direction: column;
  align-items: center;

  .logo {
    margin-top: 20px;
  }
}
</style>
