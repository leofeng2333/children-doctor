/**
 * LLM 任务接口定义 + 请求封装
 *
 * 接口：
 *   1. POST /api/ai/llm-task/bind-phone      — 绑定手机号（llmAnalysisId + phone）
 *   2. GET  /api/ai/llm-task                 — 拉取任务结果（llmAnalysisId）
 *   3. GET  /api/ai/llm-task/by-phone        — 按 phone 反查完整分析包(ai+llm)
 *
 * 业务成功判定：后端返回 code === 200（若后端用 0 改 LLM_TASK_SUCCESS_CODES 即可）。
 */

export const LLM_TASK_BASE = 'https://aiqc.hzyk.com.cn/promotion'
export const LLM_TASK_BIND_PHONE_API = `${LLM_TASK_BASE}/api/ai/llm-task/bind-phone`
export const LLM_TASK_RESULT_API = `${LLM_TASK_BASE}/api/ai/llm-task`
export const LLM_TASK_BY_PHONE_API = `${LLM_TASK_BASE}/api/ai/llm-task/by-phone`
export const LLM_TASK_SUCCESS_CODES: ReadonlySet<number> = new Set([200, 0])

export interface LlmTaskApiResponse<T = unknown> {
  ok: boolean
  status: number
  data: T | null
  raw: string
}

/**
 * 通用 POST JSON 请求（复用 smsApi 套路），返回 { ok, status, data, raw }
 *  - ok: HTTP 2xx
 *  - data: 解析后的 JSON（解析失败时为 null，原文在 raw）
 */
export async function requestLlmTaskPostApi<T = unknown>(
  url: string,
  payload: unknown,
): Promise<LlmTaskApiResponse<T>> {
  console.log('[LLM-TASK] → POST', url, payload)
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
    },
    body: JSON.stringify(payload),
  })
  const text = await res.text()
  let data: T | null = null
  try {
    data = text ? (JSON.parse(text) as T) : null
  } catch (e) {
    console.warn('[LLM-TASK] 非 JSON 响应', text)
  }
  console.log('[LLM-TASK] ← POST', res.status, data || text)
  return { ok: res.ok, status: res.status, data, raw: text }
}

/**
 * GET 请求，llmAnalysisId 作为 query 参数
 */
export async function requestLlmTaskGetApi<T = unknown>(
  url: string,
  params: Record<string, string>,
): Promise<LlmTaskApiResponse<T>> {
  const qs = new URLSearchParams(params).toString()
  const fullUrl = qs ? `${url}?${qs}` : url
  console.log('[LLM-TASK] → GET', fullUrl)
  const res = await fetch(fullUrl, {
    method: 'GET',
    headers: {
      Accept: 'application/json',
    },
  })
  const text = await res.text()
  let data: T | null = null
  try {
    data = text ? (JSON.parse(text) as T) : null
  } catch (e) {
    console.warn('[LLM-TASK] 非 JSON 响应', text)
  }
  console.log('[LLM-TASK] ← GET', res.status, data || text)
  return { ok: res.ok, status: res.status, data, raw: text }
}

/**
 * 业务成功判定：HTTP 2xx 且 业务 code 在白名单内（若无业务 code 字段仅看 HTTP 也算成功）。
 */
export function isLlmTaskSuccess(status: number, data: unknown): boolean {
  if (status < 200 || status >= 300) return false
  if (data && typeof data === 'object') {
    const bc = (data as { code?: unknown }).code
    if (typeof bc === 'number' && !LLM_TASK_SUCCESS_CODES.has(bc)) return false
  }
  return true
}
