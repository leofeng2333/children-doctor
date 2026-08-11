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
   * Sends a JPEG/bitmap to the printer.
   *
   * @param options.bitmapPath Absolute path of a JPEG file on the device.
   *                           Use the {@link DualCamera#readImageAsBase64}
   *                           pipeline or write the blob to a cache file first.
   * @param options.paperType  2=4x6, 3=5x7, 4=6x8, 5=4x6 split 2up, 6=6x6
   *                           (matches the vendor {@code PaperType} switch).
   */
  printPhoto(options: HiTiPrintPhotoOptions): Promise<HiTiResult<string>>

  /**
   * Convenience variant that accepts the JPEG as base64. The native side
   * writes the bytes to {@code getExternalCacheDir()} and forwards the
   * resulting file path to {@link printPhoto}. Use this when you already
   * have a data URL / base64 from {@code resolveImageAsDataUrl} so you
   * don't need to install {@code @capacitor/filesystem}.
   */
  printPhotoBase64(options: HiTiPrintPhotoBase64Options): Promise<HiTiResult<string>>

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
}

export interface HiTiPrintPhotoOptions {
  bitmapPath: string
  paperType?: number
}

export interface HiTiPrintPhotoBase64Options {
  /** Base64-encoded JPEG (no data: prefix). */
  base64: string
  paperType?: number
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