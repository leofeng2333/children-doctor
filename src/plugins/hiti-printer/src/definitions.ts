export interface HiTiPrinterPlugin {
  /**
   * Starts the underlying HiTi background service. Idempotent. Returns the
   * service's ErrorCode.
   */
  startService(): Promise<HiTiResult<HiTiErrorCode>>

  /**
   * Stops the HiTi background service.
   */
  stopService(): Promise<HiTiResult<HiTiErrorCode>>

  /**
   * Polls the printer's current status (idle / busy / paper-out / paper-jam / ...).
   * Returns a {@link HiTiPrinterStatus} or {@code null} when the SDK has no
   * status to report.
   */
  getPrinterStatus(): Promise<HiTiResult<HiTiPrinterStatus | null>>

  /**
   * Returns the printer model name (e.g. "P520L", "P525N", "P310W").
   */
  getModelName(): Promise<HiTiResult<string>>

  /** Returns the manufacturer serial number. */
  getSerialNumber(): Promise<HiTiResult<string>>

  /** Returns the firmware version string. */
  getFirmwareVersion(): Promise<HiTiResult<string>>

  /**
   * Returns ribbon info as `[ribbonType, remainCount]`.
   * {@code ribbonType} follows the vendor table (0=YMCKO, 1=K, 3=KO, ...).
   */
  getRibbonInfo(): Promise<HiTiResult<number[]>>

  /**
   * Returns printed-sheet counts as `[total, 4x6, 5x7, 6x8]`.
   */
  getPrintCount(): Promise<HiTiResult<number[]>>

  /**
   * Resets the printer. Used to clear most transient errors before a retry.
   */
  resetPrinter(): Promise<HiTiResult<string>>

  /**
   * Resumes a previously suspended print job.
   */
  resumeJob(): Promise<HiTiResult<string>>

  /**
   * Ejects the paper-jam mechanism. The vendor manual requires the user to
   * first open + close the ribbon cassette (cover open) before invoking this.
   */
  ejectPaperJam(): Promise<HiTiResult<string>>

  /**
   * Sends a JPEG/bitmap to the printer. Native side accepts base64 JPEG,
   * decodes it to a temp file under {@code getExternalCacheDir()}, then
   * synchronously invokes {@code serviceConnector.doService(job)} on a
   * raw {@code new Thread} (mirrors SampleAPK's
   * {@code MainActivity#operatePrinter(USB_PRINT_PHOTOS)}). Returns the
   * SDK-style string {@code "<action> -ID<id> : err <0x<hex> <desc>>"} on
   * both success and failure — TS side must inspect the result envelope's
   * {@code ok} flag first.
   *
   * <p>Defaults match SampleAPK MainActivity:
   * {@code paperType=2}, {@code printCount=1}, {@code matte=1}, {@code printMode=0}.
   */
  printPhoto(options: HiTiPrintPhotoOptions): Promise<HiTiResult<string>>

  /**
   * Opens a fresh print session log file. All subsequent native logs go to
   * this file in addition to logcat. Mirrors {@code DualCamera#startLogSession}.
   */
  startLogSession(): Promise<HiTiResult<{ path: string }>>

  /**
   * Closes the current print session log (writes footer).
   */
  closeLogSession(): Promise<HiTiResult<string>>

  /**
   * Mirror a single JS-side log line to the native session file (layer=JS).
   */
  captureLog(options: { tag?: string; msg: string }): Promise<HiTiResult<string>>

  /**
   * 页面级 init：在打印相关页面（ScanSubscription / PrintTestView）进入前调用。
   * bind HiTi ServiceConnector + 注册 USB attach/detach 监听。
   * 离开页面时必须 {@link #releaseForPage} 让 HiTi 释放 USB。
   *
   * <p>app 启动时不会自动 init（避免 HiTi 在 Camera2 启动时占 USB interface
   * 导致 Camera2 预览起不来）。
   */
  initForPage(): Promise<HiTiResult<string>>

  /**
   * 页面级 release：在打印相关页面离开时调用。unbind HiTi ServiceConnector +
   * 注销 USB 监听 + 释放 USB interface。Camera2 此时可以 claim UVC interface。
   */
  releaseForPage(): Promise<HiTiResult<string>>
}

/**
 * HiTi 打印参数。语义对齐
 * {@code com.hiti.test.MainActivity#operatePrinter(USB_PRINT_PHOTOS)}
 * 中的 4 个 PrinterOperation 实例字段：
 * <ul>
 *   <li>{@code paperType}: 2=4x6, 3=5x7, 4=6x8, 5=4x6 split 2up, 6=6x6</li>
 *   <li>{@code printCount}: 打印份数，sample 默认 1</li>
 *   <li>{@code matte}: 1=覆膜, 0=不覆膜，sample 默认 1</li>
 *   <li>{@code printMode}: 仅 P232W 有效，0=standard, 1=fine(HOD)，sample 默认 0</li>
 * </ul>
 * 缺省值与 sample MainActivity 完全一致，方便做行为对比。
 */
export interface HiTiPrintPhotoOptions {
  /** Base64-encoded JPEG (no data: prefix). */
  base64: string
  paperType?: number
  printCount?: number
  matte?: number
  printMode?: number
  /**
   * bitmap 处理模式（仅 PrintTestView 多模式对比诊断使用）。
   *
   * - 'current'（默认）：Java 不旋转不 normalize，raw bitmap 直传 SDK —— 与 v1.0.15-print-stable 一致
   * - 'cover-fit'：TS 已强制 cover-fit 到 1536×1024 landscape，Java 不动
   * - 'portrait-rotate'：Java 检测 portrait bitmap 后 90° 旋转
   * - 'portrait-rotate-normalize'：Java portrait 旋转 + normalize crop
   *
   * 生产调用方（如 ScanSubscription）不要传本字段，落到 'current' 默认。
   */
  bitmapProcessMode?: 'current' | 'cover-fit' | 'portrait-rotate' | 'portrait-rotate-normalize'
}

/**
 * Uniform return envelope. The SDK is Java-side and uses blocking calls,
 * so the TS layer wraps every call in `{ ok, data | error }` to keep the
 * caller code path uniform (and to make auto-fallback trivial: just check
 * `ok === false` and switch to the system printer).
 */
export type HiTiResult<T> =
  | { ok: true; data?: T }
  | { ok: false; error: string }

export interface HiTiErrorCode {
  value: number
  description: string
}

export interface HiTiPrinterStatus {
  statusValue: number
  statusDescription: string
}