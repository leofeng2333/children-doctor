<script setup lang="ts">
import { ref, onMounted, nextTick, onUnmounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'
import { Toast } from '@capacitor/toast'
import { useUserStore } from '@/stores'
import { saveUserInfo } from '@/utils/service'

const router = useRouter()

const isOutOfProvince = ref(false)
const chartRef = ref<HTMLDivElement | null>(null)

const echartsInstance = ref<echarts.ECharts | null>(null);
const currentMapName = ref('浙江省')

const locationList = ref<any[]>([]);

const cityMap: Record<string, { key: string; name: string; center: [number, number]; zoom: number }> = {
  '杭州市': { key: '330100', name: '杭州市', center: [119.5, 29.8], zoom: 1.2 },
  '绍兴市': { key: '330600', name: '绍兴市', center: [120.6, 29.8], zoom: 1.1 },
  '金华市': { key: '330700', name: '金华市', center: [120.0, 29.1], zoom: 1.1 },
  '义乌市': { key: '330782', name: '义乌市', center: [120.05, 29.3], zoom: 1.1 },
}

const goNext = async () => {
  if (!isOutOfProvince.value && (!locationList.value.length || cityMap[locationList.value.lastItem.name])) {
    Toast.show({
      text: '请选择县市区',
      position: 'center'
    })
    return;
  }
  const { nickname, phone } = useUserStore();
  const params: Record<string, string> = {
    nickname: nickname,
    phone: phone,
    province: isOutOfProvince.value ? '浙江省外' : '浙江省',
  }
  if (!isOutOfProvince.value) {
    params.city = locationList.value[0].name;
    params.district = locationList.value[1]?.name;
  }
  // await saveUserInfo(params)
  router.push('/diagnosis')
}

const selectOutOfProvince = () => {
  isOutOfProvince.value = true
  currentMapName.value = '浙江省';
  initMap('330000', '浙江省', [120.4, 29.1], 1.1);
  locationList.value = [];

}

const showReturn = computed(() => {
  return currentMapName.value !== '浙江省';
})

const handleReturn = () => {
  if (locationList.value.length === 3 || locationList.value.lastItem.name === '义乌市') {
    locationList.value.length = 1;
    currentMapName.value = '金华市';
    const cityInfo = cityMap['金华市']!;
    initMap(cityInfo.key, cityInfo.name, cityInfo.center, cityInfo.zoom);
  }
  else {
    locationList.value = [];
    currentMapName.value = '浙江省';
    initMap('330000', '浙江省', [120.4, 29.1], 1.1);
  }
}

const initMap = async (mapKey: string, mapName: string, center: [number, number], zoom: number) => {
  if (!chartRef.value) return
  let url = `https://geo.datav.aliyun.com/areas_v3/bound/${mapKey}_full.json`;
  if (mapKey === '330782') {
    url = '/330782.json';
  }
  const response = await fetch(url)
  const geoJson = await response.json()
  console.log('geoJson', geoJson);

  if (!echartsInstance.value) {
    echartsInstance.value = echarts.init(chartRef.value)
  }
  const chart = echarts.getInstanceByDom(chartRef.value)!;
  echarts.registerMap(mapName, geoJson)

  const option = {
    tooltip: {
      trigger: 'item',
      show: false,
    },
    series: [
      {
        name: mapName,
        type: 'map',
        map: mapName,
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
          areaColor: '#E8E8E8',
          borderColor: '#BDBDBD',
          borderWidth: 1,
        },
        emphasis: {
          label: {
            show: true,
          },
          itemStyle: {
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

  chart.setOption(option)
}

onMounted(async () => {

  await initMap('330000', '浙江省', [120.4, 29.1], 1.1);
  await nextTick();
  echartsInstance.value!.on('click', (params: any) => {
    console.log('city', params);
    isOutOfProvince.value = false;

    const cityInfo = cityMap[params.name]

    if (!locationList.value.length) {
      locationList.value.push(params);
    }

    if (params.seriesName === '浙江省') {
      locationList.value = [params];
    }

    if (cityInfo) {
      currentMapName.value = cityInfo.name
      initMap(cityInfo.key, cityInfo.name, cityInfo.center, cityInfo.zoom)
    }

    while (locationList.value.length && locationList.value.lastItem.name !== params.seriesName) {
      locationList.value.pop();
    }
    locationList.value.push(params);


  })
})

onUnmounted(() => {
  echartsInstance.value?.dispose()
  echartsInstance.value = null
})
</script>

<template>
  <div class="form-container">

    <div class="page-top-container">
      <!-- 欢迎语 -->
      <div class="welcome-text">
        Hi，<br />我是你的AI口腔医生！
      </div>

      <!-- 标题 -->
      <h1 class="form-title">我们先来填写用户的问诊单吧。</h1>

      <!-- 表单 -->
      <div class="page-content">
        <h2 class="map-title">
          你现居住在哪里？
        </h2>

        <div v-if="isOutOfProvince" class="position-list">
          <div class="position-item">浙江省外</div>
        </div>
        <div v-else class="position-list">
          <div class="position-item">{{ isOutOfProvince ? "浙江省外" : "浙江省" }}</div>
          <template v-for="item in locationList">
            <span class="position-item-separator"></span>
            <div class="position-item">{{ item.name }}</div>
          </template>
        </div>

        <div class="map-area">
          <img v-show="showReturn" src="@/assets/return.svg" alt="返回" class="return-icon" @click="handleReturn">

          <div class="position-button-area" :class="{ 'return-button-area': showReturn }">
            <div v-show="!showReturn" class="position-item position-button" @click="selectOutOfProvince">我在浙江省外</div>
          </div>
          <div ref="chartRef" class="map-container"></div>
        </div>
      </div>

    </div>

    <!-- 按钮 -->
    <div class="bottom-section-buttons">
      <!-- 按钮 -->
      <PrimaryButton text="下一步" @click="goNext" />

      <!-- Logo -->
      <LogoText class="logo" />
    </div>
  </div>
</template>

<style scoped>
.form-container {
  height: 100vh;
  height: 100dvh;
  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 30px;
  padding-top: max(42px, env(safe-area-inset-top));
  padding-bottom: calc(40px + env(safe-area-inset-bottom));
  overflow: hidden;
  justify-content: space-between;
}

/* Logo */
.logo-text {
  text-align: center;
  font-family: 'Inter', sans-serif;
  font-size: 24px;
  color: #BCBCBC;
}

/* 欢迎语 */
.welcome-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.25;
  color: #000;
  margin-top: 24px;
}

/* 标题 */
.form-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.625;
  color: #000;
  margin: 16px 0 30px 0;
}

.map-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 16px;
  font-weight: 400;
  line-height: 1.625;
  color: #000;

  margin-bottom: 8px;

}

/* 表单 */
.page-content {
  flex: 1;

  .position-list {
    display: flex;
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
    align-items: center;
  }

  .position-item {

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

  .position-item-separator {
    width: 6px;
    height: 1px;
    background-color: #000;
    margin: 0 8px;
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

    .position-button-area {
      display: flex;
      justify-content: flex-end;
      min-height: 32px;

      .position-button {
        font-weight: 700;
      }
    }

    .map-container {
      /* flex: 1; */
      /* background: #D9D9D9; */
      /* border-radius: 12px; */
      width: 100%;
      height: 360px;
    }

  }
}

.bottom-section-buttons {
  display: flex;
  flex-direction: column;
  align-items: center;

  .logo {
    margin-top: 12px;
  }
}
</style>
