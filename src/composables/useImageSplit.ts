import { ref, watch } from 'vue'
import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'

export interface ImageSplitResult {
  leftUrl: ReturnType<typeof ref<string>>
  rightUrl: ReturnType<typeof ref<string>>
  isLoading: ReturnType<typeof ref<boolean>>
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
): ImageSplitResult {
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
      })
      if (current !== token) return
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
