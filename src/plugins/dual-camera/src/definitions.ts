export interface DualCameraPhoto {
  frontCameraUrl: string
  backCameraUrl: string
  frontCameraPath: string
  backCameraPath: string
  timestamp: number
  /** 复制到外部缓存的路径，用于 H5 WebView 展示 */
  frontDisplayUrl?: string
  backDisplayUrl?: string
}

export interface DualCameraOptions {
  frontCamera?: 'front' | 'back'
  imageQuality?: 'low' | 'medium' | 'high'
  saveToGallery?: boolean
}

export interface DualCameraPreviewRect {
  x: number
  y: number
  width: number
  height: number
}

export interface DualCameraDeviceCamera {
  cameraId: string
  lensFacing: number
  previewWidth?: number
  previewHeight?: number
  captureWidth?: number
  captureHeight?: number
}

export interface DualCameraPreviewResult {
  cameras: DualCameraDeviceCamera[]
  concurrent: boolean
}

export interface DualCameraUploadOptions {
  uploadUrl: string
  files: Record<string, string[]>
  extraData?: Record<string, string>
}

export interface DualCameraUploadResult {
  response: string
}

export interface ImageSplitOptions {
  imageUrl: string
  splitRatio?: number
  /**
   * 交界处左右各向内缩的像素数（inset）。
   *
   * 默认 0 = 严格二等分（与历史行为一致）。
   * > 0 时两张图会各从交界处向内裁掉 {@code inset} 像素，
   * 用于在 before/after 卡片切换时让两张图整体宽度略小于原图，
   * 避免边缘像素拼接到同一行产生毛刺。
   *
   * 实际裁切范围会被夹紧到合法区间：
   *   - `leftEnd = max(0, halfWidth - inset)`
   *   - `rightStart = min(srcWidth, halfWidth + inset)`
   * 因此 `inset >= halfWidth` 或 `inset >= srcWidth - halfWidth` 也不会越界。
   */
  inset?: number
}

export interface ImageSplitResult {
  /** File path or data URL; WebView can render directly when it starts with file:// or http(s)://. */
  leftUrl: string
  rightUrl: string
  leftWidth?: number
  rightWidth?: number
  height?: number
}

export interface DualCameraPlugin {
  getAvailableCameras(): Promise<{ cameras: DualCameraDeviceCamera[] }>
  isDualCameraSupported(): Promise<{ supported: boolean }>
  startPreview(): Promise<DualCameraPreviewResult>
  stopPreview(): Promise<void>
  displayPhotos(options: { files: Record<string, string[]> }): Promise<void>
  resumePreviewFromPhotos(): Promise<void>
  capture(): Promise<DualCameraPhoto & Record<string, unknown>>
  uploadPhotos(options: DualCameraUploadOptions): Promise<DualCameraUploadResult>
  copyImageToExternalCache(options: { uri: string }): Promise<{ path: string; uri: string }>
  splitImage(options: ImageSplitOptions): Promise<ImageSplitResult>
  /**
   * Reads an image from any supported source (file://, content://, http(s)://,
   * data:) and returns its bytes as a base64 string (no data: prefix).
   *
   * Native: implemented in DualCameraPlugin.java (Android only).
   * Web: implemented in DualCameraWeb using fetch + FileReader.
   */
  readImageAsBase64(options: { input: string }): Promise<{ base64: string }>
  clearImageCache(): Promise<{ removed: number }>
  addListener(
    eventName: 'captureComplete',
    listener: (data: DualCameraPhoto & Record<string, unknown>) => void,
  ): Promise<{ remove: () => void }>
  addListener(
    eventName: 'previewError',
    listener: (data: { error: string }) => void,
  ): Promise<{ remove: () => void }>

  /**
   * 拍摄日志会话相关 API。仅 Android 原生实现；Web 端是 stub。
   *
   * 启动一次新会话：返回日志文件绝对路径。每次拍照流程开始调用一次。
   */
  startLogSession(): Promise<{ path: string }>
  /** 关闭当前会话：写入 footer 并停止追加。 */
  closeLogSession(): Promise<void>
  /**
   * 写一行 JS 层日志到 native 日志文件。
   * @param options.tag 模块名（默认 "JS"）
   * @param options.msg 日志内容（不能含换行）
   */
  captureLog(options: { tag?: string; msg: string }): Promise<void>
  /**
   * 查询当前会话状态：
   *   - path: 日志文件绝对路径
   *   - uri:  content:// URI（前端分享/上传用）
   *   - size: 文件字节数
   */
  getLogSessionInfo(): Promise<{ path: string | null; uri: string | null; size: number }>
}
