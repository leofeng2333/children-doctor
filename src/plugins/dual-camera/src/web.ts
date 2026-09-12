import { WebPlugin, Capacitor } from '@capacitor/core'
import type {
  DualCameraPlugin,
  DualCameraDeviceCamera,
  DualCameraOptions,
  DualCameraPreviewResult,
  DualCameraUploadOptions,
  ImageSplitOptions,
  ImageSplitResult,
  CaptureConfigPayload,
  CaptureConfigSlotPayload,
} from './definitions'

/**
 * Web 端 CaptureConfig 持久化 key。开发模式下让前端编辑配置可跨刷新生效。
 */
const WEB_CAPTURE_CONFIG_STORAGE_KEY = 'children-doctor.capture-config.v1'

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

  // 日志会话 API 的 Web stub：在浏览器上 noop 返回，避免在 web dev 时 console 报错。
  async startLogSession(): Promise<{ path: string }> {
    return { path: '' }
  }
  async closeLogSession(): Promise<void> {
    // noop
  }
  async captureLog(_options: { tag?: string; msg: string }): Promise<void> {
    // noop
  }
  async getLogSessionInfo(): Promise<{ path: string | null; uri: string | null; size: number }> {
    return { path: null, uri: null, size: 0 }
  }

  /**
   * Web 端从 localStorage 读取；缺失时回退到 native 默认值（左 270/90、右 90/90）。
   * 整个方法在 native 端会直接被 Capacitor 路由到 DualCameraPlugin.java，
   * 这里仅在浏览器 dev / iOS fallback 路径下被调用。
   */
  async getCaptureConfig(): Promise<CaptureConfigPayload> {
    const stored = readWebCaptureConfig()
    return stored ?? defaultWebCaptureConfig()
  }

  /**
   * Web 端写入 localStorage；不做运行时校准（保持冷启动路径一致）。
   * 输入做归一化：旋转角夹到 0/90/180/270，缺失 slot 用默认填充。
   */
  async setCaptureConfig(options: {
    slots: CaptureConfigSlotPayload[]
  }): Promise<CaptureConfigPayload> {
    const normalized = normalizeWebCaptureConfig(options.slots)
    if (typeof window !== 'undefined' && window.localStorage) {
      try {
        window.localStorage.setItem(
          WEB_CAPTURE_CONFIG_STORAGE_KEY,
          JSON.stringify(normalized),
        )
      } catch {
        // 写入失败仍返回归一化结果，不让前端弹错。
      }
    }
    return normalized
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

/**
 * Web 端默认配置：与 Android CaptureConfig.defaults() 完全一致。
 * 修改任一端都要同步另一端。
 */
function defaultWebCaptureConfig(): CaptureConfigPayload {
  return {
    version: 1,
    slots: defaultSlots(),
  }
}

/** 用户在弹窗里输入的最大缩放，避免误触录到很大值。 */
export const ZOOM_INPUT_MAX = 4.0

function defaultSlots(): [CaptureConfigSlotPayload, CaptureConfigSlotPayload] {
  return [
    { previewRotation: 270, captureRotation: 90, mirror: false, zoom: 1.0 },
    { previewRotation: 90, captureRotation: 90, mirror: false, zoom: 1.0 },
  ]
}

function readWebCaptureConfig(): CaptureConfigPayload | null {
  if (typeof window === 'undefined' || !window.localStorage) return null
  try {
    const raw = window.localStorage.getItem(WEB_CAPTURE_CONFIG_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as Partial<CaptureConfigPayload>
    if (!parsed || !Array.isArray(parsed.slots)) return null
    return normalizeWebCaptureConfig(parsed.slots as CaptureConfigSlotPayload[])
  } catch {
    return null
  }
}

function normalizeWebCaptureConfig(
  slots: CaptureConfigSlotPayload[],
): CaptureConfigPayload {
  const [d0, d1] = defaultSlots()
  const out: [CaptureConfigSlotPayload, CaptureConfigSlotPayload] = [
    { ...d0 },
    { ...d1 },
  ]
  for (let i = 0; i < 2; i++) {
    const fallback = i === 0 ? d0 : d1
    const incoming = slots[i] ?? fallback
    const built: CaptureConfigSlotPayload = {
      previewRotation: normalizeRotation(incoming.previewRotation),
      captureRotation: normalizeRotation(incoming.captureRotation),
      mirror: Boolean(incoming.mirror),
      zoom: normalizeZoom(incoming.zoom ?? fallback.zoom),
    }
    if (i === 0) out[0] = built
    else out[1] = built
  }
  return { version: 1, slots: out }
}

/**
 * 缩放值归一化：缺字段 / NaN / 越界都退回 1.0；上限放到 10.0（与 native 端对应），
 * 弹窗会用 ZOOM_INPUT_MAX=4.0 进一步限制输入，存档时是 4.0 落库。
 */
function normalizeZoom(z: number): number {
  if (typeof z !== 'number' || !Number.isFinite(z)) return 1.0
  // 防御：NaN、负数、0 都纠正为 1.0
  if (z < 1.0) return 1.0
  if (z > 10.0) return 10.0
  // 一位小数对齐 native / UI
  return Math.round(z * 10) / 10
}

function normalizeRotation(deg: number): number {
  if (!Number.isFinite(deg)) return 0
  const r = Math.round(deg) % 360
  return ((r + 360) % 360)
}
