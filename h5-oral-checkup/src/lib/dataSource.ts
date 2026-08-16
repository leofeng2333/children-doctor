/**
 * 数据源读取 & 合并
 *
 * 数据源优先级（先查 → 后查 → 兜底 mock）：
 *   1. URL query (?data=<base64-json>)               — 调试/分享
 *   2. sessionStorage.faceResult:analysisResult      — Vue analysisStore.result（startAnalysis 返回）
 *   3. sessionStorage.faceResult:verifyData          — SMS 验证 API 响应（input.html 写入）
 *   4. DEFAULT_MOCK                                  — 兜底 mock
 *
 * 输出统一的 ResultShape：
 *   { categoryCode, diagnosisCopy, goodImgUrl, badImgUrl, fullImgUrl }
 */

import { DiagnosisCode, type DiagnosisCodeValue } from './diagnosisCopy'

/** sessionStorage key 常量 */
export const STORAGE_KEYS: Readonly<{
  analysisResult: string
  verifyData: string
}> = {
  analysisResult: 'faceResult:analysisResult',
  verifyData: 'faceResult:verifyData',
}
Object.freeze(STORAGE_KEYS)

export interface ResultShape {
  categoryCode: DiagnosisCodeValue | null
  diagnosisCopy: null
  goodImgUrl: string
  badImgUrl: string
  fullImgUrl: string
}

/** 兜底 mock */
export const DEFAULT_MOCK: ResultShape = {
  categoryCode: DiagnosisCode.NORMAL,
  diagnosisCopy: null,
  goodImgUrl: '',
  badImgUrl: '',
  fullImgUrl: '',
}
Object.freeze(DEFAULT_MOCK)

/** 兜底填充：{ ...DEFAULT_MOCK, ...source } */
function withDefaults(source: Partial<ResultShape>): ResultShape {
  return { ...DEFAULT_MOCK, ...source }
}

/* 单个数据源读取 */
function readFromUrl(): Partial<ResultShape> | null {
  try {
    const params = new URLSearchParams(location.search)
    const raw = params.get('data')
    if (!raw) return null
    const json = decodeURIComponent(escape(atob(raw)))
    return JSON.parse(json) as Partial<ResultShape>
  } catch (err) {
    console.warn('[face-result] URL data 解析失败', err)
    return null
  }
}

function readFromStorage(key: string): unknown {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return null
    return JSON.parse(raw)
  } catch (err) {
    console.warn('[face-result] sessionStorage 读取失败', key, err)
    return null
  }
}

/* 归一化函数 */
function pickLlmResult(raw: unknown): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const data = r.data as Record<string, unknown> | undefined
  if (data?.llmAnalysis && typeof data.llmAnalysis === 'object') {
    const llm = data.llmAnalysis as Record<string, unknown>
    if (llm.result && typeof llm.result === 'object') return llm.result as Record<string, unknown>
  }
  if (r.llmAnalysis && typeof r.llmAnalysis === 'object') {
    const llm = r.llmAnalysis as Record<string, unknown>
    if (llm.result && typeof llm.result === 'object') return llm.result as Record<string, unknown>
  }
  return null
}

function pickAiResult(raw: unknown): Record<string, unknown> | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const data = r.data as Record<string, unknown> | undefined
  if (data?.aiAnalysis && typeof data.aiAnalysis === 'object') {
    const ai = data.aiAnalysis as Record<string, unknown>
    if (ai.result && typeof ai.result === 'object') return ai.result as Record<string, unknown>
  }
  if (r.aiAnalysis && typeof r.aiAnalysis === 'object') {
    const ai = r.aiAnalysis as Record<string, unknown>
    if (ai.result && typeof ai.result === 'object') return ai.result as Record<string, unknown>
  }
  return null
}

function normalize(raw: unknown): Partial<ResultShape> | null {
  const llm = pickLlmResult(raw)
  const ai = pickAiResult(raw)
  if (!llm && !ai) return null

  const categoryCode =
    typeof llm?.categoryCode === 'number'
      ? (llm.categoryCode as DiagnosisCodeValue)
      : null

  const predictions = (ai?.predictions as Record<string, unknown> | undefined) ?? undefined
  const fullImgUrl = typeof predictions?.futureImageUrl === 'string' ? predictions.futureImageUrl : ''

  return {
    categoryCode,
    diagnosisCopy: null,
    goodImgUrl: '',
    badImgUrl: '',
    fullImgUrl,
  }
}

/* 对外 */
export function resolveResult(): ResultShape {
  const urlData = readFromUrl()
  if (urlData) {
    console.log('[face-result] 数据源: URL ?data=')
    return withDefaults(urlData)
  }

  const analysisRaw = readFromStorage(STORAGE_KEYS.analysisResult)
  if (analysisRaw) {
    console.log('[face-result] 数据源: sessionStorage.faceResult:analysisResult')
    const normalized = normalize(analysisRaw)
    if (normalized) return withDefaults(normalized)
  }

  const verifyData = readFromStorage(STORAGE_KEYS.verifyData)
  if (verifyData) {
    console.log('[face-result] 数据源: sessionStorage.faceResult:verifyData')
    const normalized = normalize(verifyData)
    if (normalized) return withDefaults(normalized)
  }

  console.log('[face-result] 数据源: DEFAULT_MOCK')
  return withDefaults({})
}

export function readLlmResult(): Record<string, unknown> | null {
  const analysisRaw = readFromStorage(STORAGE_KEYS.analysisResult) as unknown
  if (analysisRaw) {
    const llm = pickLlmResult(analysisRaw)
    if (llm) return llm
  }
  const verifyData = readFromStorage(STORAGE_KEYS.verifyData) as unknown
  if (verifyData) {
    const llm = pickLlmResult(verifyData)
    if (llm) return llm
  }
  return null
}

export function loadVerifyDataToWindow(): unknown {
  const raw = readFromStorage(STORAGE_KEYS.verifyData)
  if (!raw) {
    console.log('[face-result] 没有来自校验 API 的 verifyData')
    return null
  }
  ;(window as unknown as { __verifyData?: unknown }).__verifyData = raw
  console.log('[face-result] 已加载校验数据', raw)
  return raw
}
