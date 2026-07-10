/**
 * 数据源读取 & 合并
 *
 * 数据源优先级（先查 → 后查 → 兜底 mock）：
 *   1. URL query (?data=<base64-json>)               — 调试/分享
 *   2. sessionStorage.faceResult:analysisResult      — Vue analysisStore.result（startAnalysis 返回）
 *   3. sessionStorage.faceResult:verifyData          — input.html 写入的 SMS 验证 API 响应
 *   4. DEFAULT_MOCK                                  — 兜底 mock
 *
 * 输出统一的 ResultShape：
 *   { categoryCode, diagnosisCopy, goodImgUrl, badImgUrl, fullImgUrl }
 *
 * 两个来源的结构差异：
 *
 * verifyData（SMS 验证 API，响应在 data 下）：
 *   {
 *     data: {
 *       llmAnalysis: { result: { categoryCode: number, ... } },
 *       aiAnalysis:  { result: { predictions: { currentImageUrl, futureImageUrl } } }
 *     }
 *   }
 *
 * analysisResult（/api/ai/analyze，Vue store 原样）：
 *   {
 *     llmAnalysis: { result: { categoryCode: number, ... } },
 *     aiAnalysis:  { result: { predictions: { futureImageUrl } } }
 *   }
 *
 * 图片处理（统一走 image-splitter 切割）：
 *   - fullImgUrl 优先取 predictions.futureImageUrl，
 *     由 image-splitter 按 50% 切成 badImgUrl / goodImgUrl。
 *   - goodImgUrl / badImgUrl 字段保留，作为外部预切图的覆盖入口
 *     （URL ?data= 调试场景可直接传入）。
 */

import { DiagnosisCode } from './diagnosis-copy.js'

/** sessionStorage key 常量 */
export const STORAGE_KEYS = Object.freeze({
  analysisResult: 'faceResult:analysisResult',
  verifyData: 'faceResult:verifyData',
})

/** 兜底 mock */
export const DEFAULT_MOCK = Object.freeze({
  categoryCode: DiagnosisCode.NORMAL,
  diagnosisCopy: null,
  /** 矫正后"好"面容图 URL（可由 fullImgUrl 切割得到） */
  goodImgUrl: '',
  /** 矫正前"坏"面容图 URL（可由 fullImgUrl 切割得到） */
  badImgUrl: '',
  /**
   * 整张预测图 URL。
   * 由 image-splitter 按 50% 切割为左右两半：
   *   左半 = 矫正前（坏） → badImgUrl
   *   右半 = 矫正后（好） → goodImgUrl
   */
  fullImgUrl: '',
})

/** 兜底填充：{ ...DEFAULT_MOCK, ...source } */
function withDefaults(source) {
  return { ...DEFAULT_MOCK, ...source }
}

/* ---------------------------------------------------------------------------
 * 单个数据源读取
 * ------------------------------------------------------------------------ */

function readFromUrl() {
  try {
    const params = new URLSearchParams(location.search)
    const raw = params.get('data')
    if (!raw) return null
    const json = decodeURIComponent(escape(atob(raw)))
    return JSON.parse(json)
  } catch (err) {
    console.warn('[face-result] URL data 解析失败', err)
    return null
  }
}

function readFromStorage(key) {
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return null
    return JSON.parse(raw)
  } catch (err) {
    console.warn('[face-result] sessionStorage 读取失败', key, err)
    return null
  }
}

/* ---------------------------------------------------------------------------
 * 归一化函数
 *
 * 两个来源都把图片统一收敛为 fullImgUrl，
 * 由 injector 阶段用 image-splitter 切割成 good/bad。
 * ------------------------------------------------------------------------ */

/** 从 raw 中提取 llmAnalysis.result */
function pickLlmResult(raw) {
  if (!raw || typeof raw !== 'object') return null
  // verifyData: raw.data.llmAnalysis.result
  if (raw.data?.llmAnalysis?.result) return raw.data.llmAnalysis.result
  // analysisResult: raw.llmAnalysis.result
  if (raw.llmAnalysis?.result) return raw.llmAnalysis.result
  return null
}

/** 从 raw 中提取 aiAnalysis.result（预测图信息） */
function pickAiResult(raw) {
  if (!raw || typeof raw !== 'object') return null
  if (raw.data?.aiAnalysis?.result) return raw.data.aiAnalysis.result
  if (raw.aiAnalysis?.result) return raw.aiAnalysis.result
  return null
}

/**
 * 归一化 Vue analysisStore.result / verifyData（结构差异已统一处理）：
 *   - llmAnalysis.result.categoryCode → categoryCode
 *   - aiAnalysis.result.predictions.futureImageUrl → fullImgUrl（待切割）
 */
function normalize(raw) {
  const llm = pickLlmResult(raw)
  const ai = pickAiResult(raw)
  if (!llm && !ai) return null

  const categoryCode = typeof llm?.categoryCode === 'number' ? llm.categoryCode : null
  return {
    categoryCode,
    diagnosisCopy: null,
    goodImgUrl: '',
    badImgUrl: '',
    fullImgUrl: ai?.predictions?.futureImageUrl || '',
  }
}

/* ---------------------------------------------------------------------------
 * 对外：解析当前数据
 * ------------------------------------------------------------------------ */

/**
 * 三级数据源合并，命中即短路。
 */
export function resolveResult() {
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
  return withDefaults()
}

/**
 * 读取 LLM 分析结果（llmAnalysis.result 对象）。
 * 同时支持 verifyData 和 analysisResult 两种来源。
 */
export function readLlmResult() {
  const analysisRaw = readFromStorage(STORAGE_KEYS.analysisResult)
  if (analysisRaw) {
    const llm = pickLlmResult(analysisRaw)
    if (llm) return llm
  }
  const verifyData = readFromStorage(STORAGE_KEYS.verifyData)
  if (verifyData) {
    const llm = pickLlmResult(verifyData)
    if (llm) return llm
  }
  return null
}

/**
 * 读取 verifyData 并挂到 window.__verifyData（保持原有 IIFE 行为）。
 */
export function loadVerifyDataToWindow() {
  const raw = readFromStorage(STORAGE_KEYS.verifyData)
  if (!raw) {
    console.log('[face-result] 没有来自 input.html 的校验数据')
    return null
  }
  window.__verifyData = raw
  console.log('[face-result] 已加载校验数据', raw)
  return raw
}
