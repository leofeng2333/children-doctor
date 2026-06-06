import { defineStore } from 'pinia'
import { ref } from 'vue'
import { startAnalysis } from '@/utils/service'

export const useAnalysisStore = defineStore('analysis', () => {
  const result = ref<any>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const start = async () => {
    isLoading.value = true
    error.value = null
    try {
      result.value = await startAnalysis()
      console.log('startAnalysis result', result.value)
    } catch (e: any) {
      error.value = e?.message ?? '分析失败'
    } finally {
      isLoading.value = false
    }
  }

  const reset = () => {
    result.value = null
    error.value = null
  }

  return { result, isLoading, error, start, reset }
})
