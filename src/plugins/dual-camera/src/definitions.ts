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

  /**
   * 读取拍照方向校准配置（4 个角度 + 左右镜像）。
   * 文件不存在或损坏时 native 端会自动用默认值重建；
   * Web 端通过 localStorage fallback 保留一份方便本地调试。
   */
  getCaptureConfig(): Promise<CaptureConfigPayload>

  /**
   * 写入新的拍照方向校准配置。返回归一化后的最终值（native 端会把无效字段回退到默认）。
   *
   * <p>写入仅落到磁盘，<b>不</b>立即调用 setSlotCalibration 应用到当前预览周期——
   * 用户选择走冷启动路径（下次 startPreview 读新值）。
   */
  setCaptureConfig(options: { slots: CaptureConfigSlotPayload[] }): Promise<CaptureConfigPayload>

  /**
   * 把拍照方向校准恢复到默认值（{@link CaptureConfig#defaults()}）。
   *
   * <p>native 端走 {@code applyCaptureConfigInternal(defaults)}：落盘 + 推给活跃预览。
   * Web 端用 native 默认值覆盖 localStorage。
   */
  resetCaptureConfig(): Promise<CaptureConfigPayload>
}

export interface CaptureConfigSlotPayload {
  /** 预览旋转角（0/90/180/270），写入 setCalibration 的 extraRotate */
  previewRotation: number
  /** 拍照旋转角（0/90/180/270），写入 setCaptureRotationOffset */
  captureRotation: number
  /** 是否在预览上额外水平镜像，写入 setCalibration 的 extraMirror */
  mirror: boolean
  /**
   * 拍照时独立的水平镜像翻转开关（v2 引入）。
   *
   * <p>与 {@link mirror}（预览镜像）解耦：预览镜像只影响 preview TextureView，
   * 本字段只影响拍照 JPEG / YUV 输出。
   *
   * <p>语义：{@code captureMirror=true} 时把拍照路径计算出的"基础镜像方向"再翻一次；
   * {@code captureMirror=false}（默认）= 不翻转，保持 v1 行为。
   */
  captureMirror: boolean
  /**
   * 数字缩放倍数（1.0 = 原画，>1.0 = 数字放大）。
   * preview 和 capture 共享同一个 zoom（Camera2 SCALER_CROP_REGION 同一份）。
   * 取值范围 [1.0, 10.0]，UI 输入限制 [1.0, 4.0]；落库时夹紧到 [1.0, 10.0]。
   */
  zoom: number
}

export interface CaptureConfigPayload {
  /** 配置 schema 版本（当前 1） */
  version: number
  slots: CaptureConfigSlotPayload[]
  /**
   * 当前生效的配置文件绝对路径。
   *
   * <ul>
   *   <li>native: {@code /storage/emulated/0/Android/data/<pkg>/files/capture_config.json}，
   *       adb / MTP 可直接访问，跨设备同名包路径一致</li>
   *   <li>web: localStorage key 描述（浏览器无文件系统）</li>
   * </ul>
   */
  configPath?: string
}
