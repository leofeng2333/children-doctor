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
 * 构造扫码链接：<baseUrl>/<path>?id=<idid>
 *
 * baseUrl 来自环境变量 VITE_QRCODE_BASE_URL，未配置时使用当前 origin。
 * idid 优先取 localStorage 中已存的标识（同一用户多次打印复用同一个），
 * 没有则现场生成并写回 localStorage。
 */
export function buildQrcodeUrl(path = 'follow', idid: string): string {
  const base = (import.meta.env.VITE_QRCODE_BASE_URL as string | undefined)?.trim()
  const baseUrl = base && base.length > 0 ? base : window.location.origin
  const normalizedBase = baseUrl.replace(/\/+$/, '')
  const normalizedPath = path.replace(/^\/+/, '')
  return `${normalizedBase}/${normalizedPath}?id=${encodeURIComponent(idid)}`
}

/**
 * 订阅二维码状态：返回当前扫码链接 idid。
 * 第一次访问时本地生成并持久化，后续复用同一 idid。
 */
export function useQrcodeIdid() {
  const idid = ref<string>(readStoredIdid() || generateIdid())

  if (!readStoredIdid()) {
    try {
      localStorage.setItem(STORAGE_KEY, idid.value)
    } catch {
      // localStorage 写入失败可忽略
    }
  }

  const url = computed(() => buildQrcodeUrl('follow', idid.value))

  return { idid, url }
}