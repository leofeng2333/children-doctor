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

  async splitImage(options: ImageSplitOptions): Promise<ImageSplitResult> {
    const { imageUrl, splitRatio = 0.5 } = options
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

    const img = new Image()
    img.crossOrigin = 'anonymous'

    return new Promise<ImageSplitResult>((resolve, reject) => {
      img.onload = () => {
        try {
          const halfWidth = Math.floor(img.width * splitRatio)
          const canvasL = document.createElement('canvas')
          canvasL.width = halfWidth
          canvasL.height = img.height
          const ctxL = canvasL.getContext('2d')
          if (!ctxL) throw new Error('Failed to acquire 2d context for left half')
          ctxL.drawImage(img, 0, 0, halfWidth, img.height, 0, 0, halfWidth, img.height)
          const leftUrl = canvasL.toDataURL('image/jpeg', 0.95)

          const canvasR = document.createElement('canvas')
          canvasR.width = img.width - halfWidth
          canvasR.height = img.height
          const ctxR = canvasR.getContext('2d')
          if (!ctxR) throw new Error('Failed to acquire 2d context for right half')
          ctxR.drawImage(
            img,
            halfWidth,
            0,
            img.width - halfWidth,
            img.height,
            0,
            0,
            img.width - halfWidth,
            img.height,
          )
          const rightUrl = canvasR.toDataURL('image/jpeg', 0.95)

          resolve({ leftUrl, rightUrl, leftWidth: halfWidth, rightWidth: img.width - halfWidth, height: img.height })
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
