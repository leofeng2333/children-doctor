import { computed } from 'vue'

/**
 * 把 llmAnalysisId（后端可能返 string 也可能返 number）规范化为字符串。
 * 0 / NaN / 空字符串都视为未就绪，返回空字符串让上层走占位分支。
 */
function normalizeId(raw: string | number | null | undefined): string {
  if (raw === null || raw === undefined) return ''
  if (typeof raw === 'number') {
    return Number.isFinite(raw) && raw > 0 ? String(raw) : ''
  }
  const trimmed = raw.trim()
  return trimmed.length > 0 ? trimmed : ''
}

/**
 * 构造扫码链接：<baseUrl>/<path>?id=<id>
 *
 * baseUrl 来自环境变量 VITE_QRCODE_BASE_URL，未配置时使用当前 origin。
 * `id` 是后端 /api/ai/analyze 返回的 llmAnalysisId（公众号 H5 据此关联
 * 这次面型分析），不持久化到本地——避免老链接被扫到查无结果。
 * 后端当前以 number 返回，规范化在 useQrcodeIdid 内部完成，这里接受字符串。
 */
export function buildQrcodeUrl(path = 'follow', id: string): string {
  const base = (import.meta.env.VITE_QRCODE_BASE_URL as string | undefined)?.trim()
  const baseUrl = base && base.length > 0 ? base : window.location.origin
  const normalizedBase = baseUrl.replace(/\/+$/, '')
  const normalizedPath = path.replace(/^\/+/, '')
  return `${normalizedBase}/${normalizedPath}?id=${encodeURIComponent(id)}`
}

/**
 * 订阅二维码 URL：根据外部 `getId()` 返回值实时计算。
 *
 * - getId 返回非空 string / 正 number → `<baseUrl>/follow?id=<id>`
 * - getId 返回空 / undefined / 0 / NaN / 空字符串 → 空字符串
 * - 不做任何本地缓存——id 必须来自后端本次分析的 llmAnalysisId，否则
 *   扫到公众号 H5 也查不到结果，对用户造成误导。
 */
export function useQrcodeIdid(getId: () => string | number | null | undefined) {
  const url = computed(() => {
    const id = normalizeId(getId())
    return id ? buildQrcodeUrl('follow', id) : ''
  })

  return { url }
}