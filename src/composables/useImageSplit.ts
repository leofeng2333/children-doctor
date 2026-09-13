import { ref, watch } from 'vue'
import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'

export interface ImageSplitResult {
  leftUrl: ReturnType<typeof ref<string>>
  rightUrl: ReturnType<typeof ref<string>>
  isLoading: ReturnType<typeof ref<boolean>>
}

export interface UseImageSplitOptions {
  /**
   * 交界处左右各向内缩的像素数（inset）。默认 0 = 严格二等分。
   */
  inset?: number
}

/**
 * Returns a WebView-friendly URL. On native platforms `file://` paths must
 * be rewritten to the local server URL so the WebView can load them.
 */
function toDisplayUrl(path: string): string {
  if (!path) return ''
  if (path.startsWith('https://') || path.startsWith('data:')) {
    return path
  }
  if (Capacitor.isNativePlatform && Capacitor.isNativePlatform()) {
    return Capacitor.convertFileSrc(path)
  }
  return path
}

export function useImageSplit(
  imageUrl: string | (() => string | undefined),
  splitRatio: number = 0.5,
  options: UseImageSplitOptions | number = {},
): ImageSplitResult {
  // 兼容旧调用 useImageSplit(url, ratio, insetPx)
  const inset =
    typeof options === 'number' ? options : (options?.inset ?? 0)
  const leftUrl = ref<string>('')
  const rightUrl = ref<string>('')
  const isLoading = ref(false)

  let token = 0

  async function splitImage(url: string) {
    if (!url) {
      leftUrl.value = ''
      rightUrl.value = ''
      return
    }

    const current = ++token
    isLoading.value = true

    try {
      const { leftUrl: l, rightUrl: r } = await DualCamera.splitImage({
        imageUrl: url,
        splitRatio,
        inset,
      })
      if (current !== token) return
      // 业务上需要"好图在前、坏图在后"。
      // 原生插件 splitImage 返回的 leftUrl 实际是原图左半（矫正前的"坏"面容），
      // rightUrl 是原图右半（矫正后的"好"面容）。
      // 这里把两个 ref 交叉赋值，使下游拿到的 leftUrl 指向"好"图、rightUrl 指向"坏"图。
      leftUrl.value = toDisplayUrl(l)
      rightUrl.value = toDisplayUrl(r)
    } catch (err) {
      if (current !== token) return
      console.warn(
        `[useImageSplit] split failed for ${url}: ${
          err && typeof err === 'object' && 'message' in err
            ? (err as { message?: string }).message
            : String(err)
        }`,
      )
      leftUrl.value = ''
      rightUrl.value = ''
    } finally {
      if (current === token) {
        isLoading.value = false
      }
    }
  }

  watch(
    () => (typeof imageUrl === 'function' ? imageUrl() : imageUrl),
    (url) => {
      if (url) splitImage(url)
    },
    { immediate: true },
  )

  return { leftUrl, rightUrl, isLoading }
}
