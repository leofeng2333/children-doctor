import { defineStore } from 'pinia'
import { ref } from 'vue'
import { startAnalysis } from '@/utils/service'
import { type HabitCode } from '@/utils/diagnosisCopy'

export const useAnalysisStore = defineStore('analysis', () => {
  const result = ref<any>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)
  /** 根据问卷回答确定的坏习惯编码（去重） */
  const badHabits = ref<HabitCode[]>([])

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
    badHabits.value = []
  }

  /** 设置坏习惯编码（由 QuestionView 提交问卷后调用） */
  const setBadHabits = (habits: HabitCode[]) => {
    badHabits.value = habits
  }

  /** 清除坏习惯（与 reset 保持一致） */
  const clearBadHabits = () => {
    badHabits.value = []
  }

  return { result, isLoading, error, badHabits, start, reset, setBadHabits, clearBadHabits }
})
