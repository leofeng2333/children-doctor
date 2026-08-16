import { computed, ref } from 'vue'

const STORAGE_KEY = 'children_doctor.qrcode_idid'

/**
 * 生成一个 8 位十六进制随机字符串作为本地 idid。
 * 用 crypto.randomUUID() 截取，避免使用 Math.random。
 */
function generateIdid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '').slice(0, 16)
  }
  // Fallback: 时间戳 + 随机数（基本不可能走到）
  return Date.now().toString(16) + Math.floor(Math.random() * 0xfffff).toString(16)
}

function readStoredIdid(): string {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw && /^[a-f0-9]+$/i.test(raw)) return raw
  } catch {
    // localStorage 不可用时回退到本次会话内 idid
  }
  return ''
}

/**
 * 构造扫码链接：<baseUrl>/<path>?id=<id>
 *
 * baseUrl 来自环境变量 VITE_QRCODE_BASE_URL，未配置时使用当前 origin。
 * `id` 通常是后端返回的 llmAnalysisId（公众号 H5 据此关联这次面型分析），
 * 在 llmAnalysisId 尚未就绪时调用方会回退到本地持久化的 idid。
 */
export function buildQrcodeUrl(path = 'follow', id: string): string {
  const base = (import.meta.env.VITE_QRCODE_BASE_URL as string | undefined)?.trim()
  const baseUrl = base && base.length > 0 ? base : window.location.origin
  const normalizedBase = baseUrl.replace(/\/+$/, '')
  const normalizedPath = path.replace(/^\/+/, '')
  return `${normalizedBase}/${normalizedPath}?id=${encodeURIComponent(id)}`
}

/**
 * 订阅二维码状态：返回当前扫码链接。
 *
 * - 优先使用 `overrideId`（来自后端 /api/ai/analyze 的 `llmAnalysisId`），
 *   若未提供或为空，回退到 localStorage 中持久化的本地 idid。
 * - 同一 `overrideId` 下多次打印复用同一个二维码 URL，避免热敏打印机
 *   重复出纸时给到不同链接。
 */
export function useQrcodeIdid(overrideId?: () => string | undefined) {
  const idid = ref<string>(readStoredIdid() || generateIdid())

  if (!readStoredIdid()) {
    try {
      localStorage.setItem(STORAGE_KEY, idid.value)
    } catch {
      // localStorage 写入失败可忽略
    }
  }

  const url = computed(() => {
    const external = overrideId?.()?.trim()
    return buildQrcodeUrl('follow', external && external.length > 0 ? external : idid.value)
  })

  return { idid, url }
}