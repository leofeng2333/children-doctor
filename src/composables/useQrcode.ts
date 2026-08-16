import { computed } from 'vue'

/**
 * 构造扫码链接：<baseUrl>/<path>?id=<id>
 *
 * baseUrl 来自环境变量 VITE_QRCODE_BASE_URL，未配置时使用当前 origin。
 * `id` 是后端 /api/ai/analyze 返回的 llmAnalysisId（公众号 H5 据此关联
 * 这次面型分析），不持久化到本地——避免老链接被扫到查无结果。
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
 * getId 返回非空字符串时 → `<baseUrl>/follow?id=<id>`
 * getId 返回空 / undefined / 空字符串时 → 空字符串
 * 不做任何本地缓存——id 必须来自后端本次分析的 llmAnalysisId，否则扫到
 * 公众号 H5 也查不到结果，对用户造成误导。
 */
export function useQrcodeIdid(getId: () => string | undefined) {
  const url = computed(() => {
    const raw = getId()?.trim()
    return raw && raw.length > 0 ? buildQrcodeUrl('follow', raw) : ''
  })

  return { url }
}
