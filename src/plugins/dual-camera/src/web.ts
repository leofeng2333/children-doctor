import { WebPlugin, Capacitor } from '@capacitor/core'
import type {
  DualCameraPlugin,
  DualCameraDeviceCamera,
  DualCameraOptions,
  DualCameraPreviewResult,
  DualCameraUploadOptions,
  ImageSplitOptions,
  ImageSplitResult,
} from './definitions'

function blobToBase64(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = reader.result as string
      const comma = result.indexOf(',')
      resolve(comma >= 0 ? result.slice(comma + 1) : result)
    }
    reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'))
    reader.readAsDataURL(blob)
  })
}

/**
 * Web-side fallback for the DualCamera plugin.
 *
 * On Android, Capacitor routes {@link DualCameraPlugin.splitImage} to the
 * native implementation in DualCameraPlugin.java. This class only runs in
 * the browser (or iOS), so {@link splitImage} delegates to a Canvas-based
 * implementation that returns data: URLs.
 */
export class DualCameraWeb extends WebPlugin implements DualCameraPlugin {
  async getAvailableCameras(): Promise<{ cameras: DualCameraDeviceCamera[] }> {
    throw this.unavailable('Dual camera is only available on Android.')
  }

  async isDualCameraSupported(): Promise<{ supported: boolean }> {
    throw this.unavailable('Dual camera is only available on Android.')
  }

  async startPreview(_options?: DualCameraOptions): Promise<DualCameraPreviewResult> {
    throw this.unavailable('Dual camera preview is only available on Android.')
  }

  async stopPreview(): Promise<void> {
    throw this.unavailable('Dual camera preview is only available on Android.')
  }

  async displayPhotos(_options: { files: Record<string, string[]> }): Promise<void> {
    throw this.unavailable('displayPhotos is only available on Android.')
  }

  async resumePreviewFromPhotos(): Promise<void> {
    throw this.unavailable('resumePreviewFromPhotos is only available on Android.')
  }

  async capture(): Promise<never> {
    throw this.unavailable('capture is only available on Android.')
  }

  async copyImageToExternalCache(_options: { uri: string }): Promise<{ path: string; uri: string }> {
    throw this.unavailable('copyImageToExternalCache is only available on Android.')
  }

  async uploadPhotos(_options: DualCameraUploadOptions): Promise<{ response: string }> {
    throw this.unavailable('Dual camera upload is only available on Android.')
  }

  async clearImageCache(): Promise<{ removed: number }> {
    throw this.unavailable('clearImageCache is only available on Android.')
  }

  /**
   * Reads an image from any supported source and returns base64.
   *
   * Used by the print pipeline: the native WebView backing the print dialog
   * runs in a null/data: origin and refuses to load file:// resources. By
   * inlining the image as data:image/jpeg;base64,... we sidestep the
   * Same-Origin Policy. The web fallback here lets the same code path run
   * during dev/testing in the browser.
   */
  async readImageAsBase64(options: { input: string }): Promise<{ base64: string }> {
    const { input } = options
    if (!input) throw new Error('input is required')

    if (input.startsWith('data:')) {
      const comma = input.indexOf(',')
      if (comma < 0) throw new Error('Malformed data URL')
      return { base64: input.slice(comma + 1) }
    }

    if (input.startsWith('http://') || input.startsWith('https://')) {
      const res = await fetch(input)
      if (!res.ok) throw new Error(`HTTP ${res.status} when fetching ${input}`)
      const blob = await res.blob()
      return { base64: await blobToBase64(blob) }
    }

    if (typeof fetch === 'function' && input.startsWith('file://')) {
      const res = await fetch(input)
      if (!res.ok) throw new Error(`HTTP ${res.status} when fetching ${input}`)
      const blob = await res.blob()
      return { base64: await blobToBase64(blob) }
    }

    throw new Error(
      `readImageAsBase64 cannot resolve "${input}" on this platform. ` +
        'Use a data:, http(s)://, or file:// URL.',
    )
  }

  async splitImage(options: ImageSplitOptions): Promise<ImageSplitResult> {
    const { imageUrl, splitRatio = 0.5, inset = 0 } = options
    if (!imageUrl) {
      return { leftUrl: '', rightUrl: '' }
    }

    // Native path: just resolve. Capacitor already routed to Android impl
    // when running on device; this branch is hit on web / iOS.
    if (Capacitor.isNativePlatform() && Capacitor.getPlatform() !== 'web') {
      throw this.unavailable(
        'splitImage native implementation should have handled this call.',
      )
    }

    if (typeof document === 'undefined' || typeof Image === 'undefined') {
      throw this.unavailable('splitImage is only available in a browser environment.')
    }

    const safeInset = Math.max(0, Math.floor(inset))

    const img = new Image()
    img.crossOrigin = 'anonymous'

    return new Promise<ImageSplitResult>((resolve, reject) => {
      img.onload = () => {
        try {
          const halfWidth = Math.floor(img.width * splitRatio)
          if (halfWidth <= 0 || halfWidth >= img.width) {
            throw new Error(`Invalid split ratio: ${splitRatio}`)
          }

          // 左右各向内缩 inset 像素；夹紧到 [0, img.width] 内并确保不交叉。
          // left  = [0, halfWidth - inset]
          // right = [halfWidth + inset, img.width]
          const leftEnd = Math.max(0, halfWidth - safeInset)
          const rightStart = Math.min(img.width, halfWidth + safeInset)
          if (leftEnd <= 0 || rightStart >= img.width || leftEnd >= rightStart) {
            throw new Error(`Invalid inset: ${safeInset}`)
          }

          const leftW = leftEnd
          const rightW = img.width - rightStart

          const canvasL = document.createElement('canvas')
          canvasL.width = leftW
          canvasL.height = img.height
          const ctxL = canvasL.getContext('2d')
          if (!ctxL) throw new Error('Failed to acquire 2d context for left half')
          ctxL.drawImage(img, 0, 0, leftW, img.height, 0, 0, leftW, img.height)
          const leftUrl = canvasL.toDataURL('image/jpeg', 0.95)

          const canvasR = document.createElement('canvas')
          canvasR.width = rightW
          canvasR.height = img.height
          const ctxR = canvasR.getContext('2d')
          if (!ctxR) throw new Error('Failed to acquire 2d context for right half')
          ctxR.drawImage(img, rightStart, 0, rightW, img.height, 0, 0, rightW, img.height)
          const rightUrl = canvasR.toDataURL('image/jpeg', 0.95)

          resolve({ leftUrl, rightUrl, leftWidth: leftW, rightWidth: rightW, height: img.height })
        } catch (err) {
          reject(err instanceof Error ? err : new Error(String(err)))
        } finally {
          img.src = ''
        }
      }

      img.onerror = () => {
        img.src = ''
        reject(new Error(`Failed to load image: ${imageUrl}`))
      }

      img.src = imageUrl
    })
  }
}
