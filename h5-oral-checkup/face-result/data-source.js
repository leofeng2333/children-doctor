/**
 * 数据源读取 & 合并
 *
 * 数据源优先级（先查 → 后查 → 兜底 mock）：
 *   1. URL query (?data=<base64-json>)               — 调试/分享
 *   2. sessionStorage.faceResult:analysisResult      — Vue analysisStore.result（startAnalysis 返回）
 *   3. sessionStorage.faceResult:verifyData          — input-default.html 写入的 SMS 验证 API 响应
 *   4. DEFAULT_MOCK                                  — 兜底 mock
 *
 * 输出统一的 ResultShape：
 *   { categoryCode, diagnosisCopy, goodImgUrl, badImgUrl, fullImgUrl }
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
  goodImgUrl: '',
  badImgUrl: '',
  fullImgUrl: '',
})

/** 兜底填充：{ ...DEFAULT_MOCK, ...source } */
function withDefaults(source) {
  return { ...DEFAULT_MOCK, ...source }
}

/* 单个数据源读取 */
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

/* 归一化函数 */
function pickLlmResult(raw) {
  if (!raw || typeof raw !== 'object') return null
  if (raw.data?.llmAnalysis?.result) return raw.data.llmAnalysis.result
  if (raw.llmAnalysis?.result) return raw.llmAnalysis.result
  return null
}

function pickAiResult(raw) {
  if (!raw || typeof raw !== 'object') return null
  if (raw.data?.aiAnalysis?.result) return raw.data.aiAnalysis.result
  if (raw.aiAnalysis?.result) return raw.aiAnalysis.result
  return null
}

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

/* 对外 */
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

export function loadVerifyDataToWindow() {
  const raw = readFromStorage(STORAGE_KEYS.verifyData)
  if (!raw) {
    console.log('[face-result] 没有来自 input-default.html 的校验数据')
    return null
  }
  window.__verifyData = raw
  console.log('[face-result] 已加载校验数据', raw)
  return raw
}