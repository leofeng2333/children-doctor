<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import PrimaryButton from '../components/PrimaryButton.vue'
import LogoText from '@/components/LogoText.vue'
import { Toast } from '@capacitor/toast'
import { useUserStore } from '@/stores'
import { saveUserInfo } from '@/utils/service'

const router = useRouter()

const isOutOfProvince = ref(false)
const btnLoading = ref(false)

const provinceList = [
  { label: '北京市', value: 'BJ' },
  { label: '天津市', value: 'TJ' },
  { label: '河北省', value: 'HE' },
  { label: '山西省', value: 'SX' },
  { label: '内蒙古自治区', value: 'NM' },
  { label: '辽宁省', value: 'LN' },
  { label: '吉林省', value: 'JL' },
  { label: '黑龙江省', value: 'HL' },
  { label: '上海市', value: 'SH' },
  { label: '江苏省', value: 'JS' },
  { label: '浙江省', value: 'ZJ' },
  { label: '安徽省', value: 'AH' },
  { label: '福建省', value: 'FJ' },
  { label: '江西省', value: 'JX' },
  { label: '山东省', value: 'SD' },
  { label: '河南省', value: 'HA' },
  { label: '湖北省', value: 'HB' },
  { label: '湖南省', value: 'HN' },
  { label: '广东省', value: 'GD' },
  { label: '广西壮族自治区', value: 'GX' },
  { label: '海南省', value: 'HI' },
  { label: '重庆市', value: 'CQ' },
  { label: '四川省', value: 'SC' },
  { label: '贵州省', value: 'GZ' },
  { label: '云南省', value: 'YN' },
  { label: '西藏自治区', value: 'XZ' },
  { label: '陕西省', value: 'SN' },
  { label: '甘肃省', value: 'GS' },
  { label: '青海省', value: 'QH' },
  { label: '宁夏回族自治区', value: 'NX' },
  { label: '新疆维吾尔自治区', value: 'XJ' },
  { label: '台湾省', value: 'TW' },
  { label: '香港特别行政区', value: 'HK' },
  { label: '澳门特别行政区', value: 'MO' },
]

const cityData: Record<string, { label: string; value: string; districts: { label: string; value: string }[] }[]> = {
  ZJ: [
    {
      label: '杭州市', value: '330100', districts: [
        { label: '上城区', value: '330102' }, { label: '下城区', value: '330103' },
        { label: '西湖区', value: '330105' }, { label: '拱墅区', value: '330105' },
        { label: '江干区', value: '330104' }, { label: '滨江区', value: '330108' },
        { label: '萧山区', value: '330109' }, { label: '余杭区', value: '330110' },
        { label: '临平区', value: '330113' }, { label: '钱塘区', value: '330114' },
        { label: '富阳区', value: '330111' }, { label: '临安区', value: '330112' },
        { label: '桐庐县', value: '330122' }, { label: '建德市', value: '330182' },
        { label: '淳安县', value: '330127' },
      ]
    },
    {
      label: '宁波市', value: '330200', districts: [
        { label: '海曙区', value: '330203' }, { label: '江北区', value: '330205' },
        { label: '北仑区', value: '330206' }, { label: '镇海区', value: '330211' },
        { label: '鄞州区', value: '330212' }, { label: '奉化区', value: '330213' },
        { label: '余姚市', value: '330281' }, { label: '慈溪市', value: '330282' },
        { label: '象山县', value: '330225' }, { label: '宁海县', value: '330226' },
      ]
    },
    {
      label: '温州市', value: '330300', districts: [
        { label: '鹿城区', value: '330302' }, { label: '龙湾区', value: '330303' },
        { label: '瓯海区', value: '330304' }, { label: '洞头区', value: '330305' },
        { label: '瑞安市', value: '330381' }, { label: '乐清市', value: '330382' },
        { label: '永嘉县', value: '330324' }, { label: '平阳县', value: '330326' },
        { label: '苍南县', value: '330327' }, { label: '文成县', value: '330328' },
        { label: '泰顺县', value: '330329' },
      ]
    },
    {
      label: '嘉兴市', value: '330400', districts: [
        { label: '南湖区', value: '330402' }, { label: '秀洲区', value: '330411' },
        { label: '海宁市', value: '330481' }, { label: '平湖市', value: '330482' },
        { label: '桐乡市', value: '330483' }, { label: '嘉善县', value: '330421' },
        { label: '海盐县', value: '330424' },
      ]
    },
    {
      label: '湖州市', value: '330500', districts: [
        { label: '吴兴区', value: '330502' }, { label: '南浔区', value: '330503' },
        { label: '德清县', value: '330521' }, { label: '长兴县', value: '330522' },
        { label: '安吉县', value: '330523' },
      ]
    },
    {
      label: '绍兴市', value: '330600', districts: [
        { label: '越城区', value: '330602' }, { label: '柯桥区', value: '330603' },
        { label: '上虞区', value: '330604' }, { label: '诸暨市', value: '330681' },
        { label: '嵊州市', value: '330683' }, { label: '新昌县', value: '330624' },
      ]
    },
    {
      label: '金华市', value: '330700', districts: [
        { label: '婺城区', value: '330702' }, { label: '金东区', value: '330703' },
        { label: '兰溪市', value: '330781' }, { label: '义乌市', value: '330782' },
        { label: '东阳市', value: '330783' }, { label: '永康市', value: '330784' },
        { label: '武义县', value: '330723' }, { label: '浦江县', value: '330726' },
        { label: '磐安县', value: '330727' },
      ]
    },
    {
      label: '衢州市', value: '330800', districts: [
        { label: '柯城区', value: '330802' }, { label: '衢江区', value: '330803' },
        { label: '江山市', value: '330881' }, { label: '常山县', value: '330822' },
        { label: '开化县', value: '330824' }, { label: '龙游县', value: '330825' },
      ]
    },
    {
      label: '舟山市', value: '330900', districts: [
        { label: '定海区', value: '330902' }, { label: '普陀区', value: '330903' },
        { label: '岱山县', value: '330921' }, { label: '嵊泗县', value: '330922' },
      ]
    },
    {
      label: '台州市', value: '331000', districts: [
        { label: '椒江区', value: '331002' }, { label: '黄岩区', value: '331003' },
        { label: '路桥区', value: '331004' }, { label: '临海市', value: '331082' },
        { label: '温岭市', value: '331081' }, { label: '玉环市', value: '331083' },
        { label: '三门县', value: '331022' }, { label: '天台县', value: '331023' },
        { label: '仙居县', value: '331024' },
      ]
    },
    {
      label: '丽水市', value: '331100', districts: [
        { label: '莲都区', value: '331102' }, { label: '龙泉市', value: '331181' },
        { label: '青田县', value: '331121' }, { label: '缙云县', value: '331122' },
        { label: '遂昌县', value: '331123' }, { label: '松阳县', value: '331124' },
        { label: '云和县', value: '331125' }, { label: '庆元县', value: '331126' },
        { label: '景宁畲族自治县', value: '331127' },
      ]
    },
  ],
}

const selectedProvince = ref('')
const selectedCity = ref('')
const selectedDistrict = ref('')
const cities = computed(() => cityData[selectedProvince.value] || [])
const districts = computed(() => {
  const city = cities.value.find(c => c.value === selectedCity.value)
  return city ? city.districts : []
})

const provinceName = computed(() => provinceList.find(p => p.value === selectedProvince.value)?.label || '')
const cityName = computed(() => cities.value.find(c => c.value === selectedCity.value)?.label || '')
const districtName = computed(() => districts.value.find(d => d.value === selectedDistrict.value)?.label || '')

const displayLocation = computed(() => {
  if (isOutOfProvince.value) return [{ label: '浙江省外', value: '' }]
  const parts: { label: string; value: string }[] = []
  if (provinceName.value) parts.push({ label: provinceName.value, value: selectedProvince.value })
  if (cityName.value) parts.push({ label: cityName.value, value: selectedCity.value })
  if (districtName.value) parts.push({ label: districtName.value, value: selectedDistrict.value })
  return parts
})

const handleProvinceChange = () => {
  selectedCity.value = ''
  selectedDistrict.value = ''
}

const handleCityChange = () => {
  selectedDistrict.value = ''
}

const goNext = async () => {
  if (isOutOfProvince.value) {
    // skip city/district selection for out-of-province
  } else if (!selectedProvince.value) {
    Toast.show({ text: '请选择省份', position: 'center' })
    return
  } else if (!selectedCity.value) {
    Toast.show({ text: '请选择城市', position: 'center' })
    return
  } else if (districts.value.length > 0 && !selectedDistrict.value) {
    Toast.show({ text: '请选择区县', position: 'center' })
    return
  }

  const { nickname, phone } = useUserStore()
  const params: Record<string, string> = {
    nickname,
    phone,
    province: isOutOfProvince.value ? '浙江省外' : provinceName.value,
  }
  if (!isOutOfProvince.value) {
    params.city = cityName.value
    if (selectedDistrict.value) params.district = districtName.value
  }

  btnLoading.value = true
  const tempResponse = await saveUserInfo(params).finally(() => {
    btnLoading.value = false
  })
  console.log('tempResponse', tempResponse)
  router.push('/diagnosis')
}

const selectOutOfProvince = () => {
  isOutOfProvince.value = true
  selectedProvince.value = ''
  selectedCity.value = ''
  selectedDistrict.value = ''
}

const clearOutOfProvince = () => {
  isOutOfProvince.value = false
}
</script>

<template>
  <div class="form-container">
    <div class="page-top-container">
      <div class="welcome-text">
        Hi，<br />我是你的AI口腔医生！
      </div>
      <h1 class="form-title">我们先来填写用户的问诊单吧。</h1>

      <div class="page-content">
        <h2 class="location-title">
          你现居住在哪里？
        </h2>

        <div class="position-list">
          <div v-for="(item, index) in displayLocation" :key="item.value || index" class="position-item">
            {{ item.label }}
          </div>
        </div>

        <div class="location-selects">
          <div v-if="isOutOfProvince" class="out-of-province-hint">
            <div class="hint-text">已选择浙江省外</div>
            <div class="change-btn" @click="clearOutOfProvince">修改</div>
          </div>

          <template v-else>
            <div class="select-group">
              <label class="select-label">省/直辖市/自治区</label>
              <div class="custom-select">
                <select v-model="selectedProvince" @change="handleProvinceChange">
                  <option value="" disabled>请选择省份</option>
                  <option v-for="p in provinceList" :key="p.value" :value="p.value">
                    {{ p.label }}
                  </option>
                </select>
              </div>
            </div>

            <div v-if="cities.length > 0" class="select-group">
              <label class="select-label">城市</label>
              <div class="custom-select">
                <select v-model="selectedCity" @change="handleCityChange">
                  <option value="" disabled>请选择城市</option>
                  <option v-for="c in cities" :key="c.value" :value="c.value">
                    {{ c.label }}
                  </option>
                </select>
              </div>
            </div>

            <div v-if="districts.length > 0" class="select-group">
              <label class="select-label">区县</label>
              <div class="custom-select">
                <select v-model="selectedDistrict">
                  <option value="" disabled>请选择区县</option>
                  <option v-for="d in districts" :key="d.value" :value="d.value">
                    {{ d.label }}
                  </option>
                </select>
              </div>
            </div>

            <div class="out-of-province-btn" @click="selectOutOfProvince">
              我在浙江省外
            </div>
          </template>
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

  background: #FFFFFF;
  display: flex;
  flex-direction: column;
  padding: 0 90px;
  padding-top: max(135px, env(safe-area-inset-top));
  padding-bottom: calc(42px + env(safe-area-inset-bottom));
  overflow-y: auto;
  justify-content: space-between;
}

.welcome-text {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 64px;
  font-weight: 700;
  line-height: 80px;
  color: #000;
}

.form-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin: 16px 0 30px 0;
}

.page-content {
  margin-top: 20px;
}

.location-title {
  font-family: 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
  font-size: 32px;
  font-weight: 400;
  line-height: 52px;
  color: #000;
  margin-bottom: 12px;
}

.position-list {
  display: flex;
  flex-direction: row;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 24px;
}

.position-item {
  padding: 12px 26px;
  color: #000;
  font-size: 24px;
  background: #D9D9D9;
  border-radius: 50px;
  line-height: 1;
  box-sizing: border-box;
  min-height: 32px;
  min-width: 48px;
}

.location-selects {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.select-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.select-label {
  font-size: 18px;
  color: #666;
  font-weight: 500;
}

.custom-select {
  position: relative;
}

.custom-select select {
  width: 100%;
  padding: 16px 20px;
  font-size: 20px;
  border: 1px solid #D9D9D9;
  border-radius: 12px;
  background: #fff;
  color: #000;
  appearance: none;
  -webkit-appearance: none;
  cursor: pointer;
  outline: none;
  box-sizing: border-box;
}

.custom-select select:focus {
  border-color: #FF9900;
}

.out-of-province-btn {
  margin-top: 12px;
  padding: 16px 20px;
  font-size: 20px;
  color: #666;
  text-align: center;
  cursor: pointer;
  border-radius: 12px;
}

.out-of-province-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: #f5f5f5;
  border-radius: 12px;
}

.hint-text {
  font-size: 20px;
  color: #000;
}

.change-btn {
  font-size: 18px;
  color: #FF9900;
  cursor: pointer;
  font-weight: 500;
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
