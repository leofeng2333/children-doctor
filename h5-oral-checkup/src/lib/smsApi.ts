/**
 * SMS 接口定义 + 请求封装
 *
 * 业务成功判定：后端返回 code === 200（若后端用 0 改 SMS_SUCCESS_CODES 即可）。
 */

export const SMS_API = 'https://aiqc.hzyk.com.cn/promotion/api/sms/send'
export const SMS_VERIFY_API = 'https://aiqc.hzyk.com.cn/promotion/api/sms/verify'
export const SMS_SUCCESS_CODES: ReadonlySet<number> = new Set([200, 0])

export interface SmsApiResponse<T = unknown> {
  ok: boolean
  status: number
  data: T | null
  raw: string
}

/**
 * 通用 POST JSON 请求，返回 { ok, status, data, raw }
 *  - ok: HTTP 2xx
 *  - data: 解析后的 JSON（解析失败时为 null，原文在 raw）
 */
export async function requestSmsApi<T = unknown>(
  url: string,
  payload: unknown,
): Promise<SmsApiResponse<T>> {
  console.log('[SMS] →', url, payload)
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
    console.warn('[SMS] 非 JSON 响应', text)
  }
  console.log('[SMS] ←', res.status, data || text)
  return { ok: res.ok, status: res.status, data, raw: text }
}
