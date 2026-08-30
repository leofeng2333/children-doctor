export interface HiTiPrinterCurrentPlugin {
  startService(): Promise<HiTiCurrentResult<HiTiCurrentErrorCode>>
  stopService(): Promise<HiTiCurrentResult<HiTiCurrentErrorCode>>
  getPrinterStatus(): Promise<HiTiCurrentResult<HiTiCurrentPrinterStatus | null>>
  getModelName(): Promise<HiTiCurrentResult<string>>
  getSerialNumber(): Promise<HiTiCurrentResult<string>>
  getFirmwareVersion(): Promise<HiTiCurrentResult<string>>
  getRibbonInfo(): Promise<HiTiCurrentResult<number[]>>
  getPrintCount(): Promise<HiTiCurrentResult<number[]>>
  resetPrinter(): Promise<HiTiCurrentResult<string>>
  resumeJob(): Promise<HiTiCurrentResult<string>>
  ejectPaperJam(): Promise<HiTiCurrentResult<string>>
  /**
   * path-current 打印入口：不接受 bitmapProcessMode 参数。
   * 走 single-path 策略：Java fast path 缩放到 1844×1240 + warmupGate + retry。
   */
  printPhoto(options: HiTiCurrentPrintPhotoOptions): Promise<HiTiCurrentResult<string>>
  startLogSession(): Promise<HiTiCurrentResult<{ path: string }>>
  closeLogSession(): Promise<HiTiCurrentResult<string>>
  captureLog(options: { tag?: string; msg: string }): Promise<HiTiCurrentResult<string>>
  initForPage(): Promise<HiTiCurrentResult<string>>
  releaseForPage(): Promise<HiTiCurrentResult<string>>
}

export interface HiTiCurrentPrintPhotoOptions {
  base64: string
  paperType?: number
  printCount?: number
  matte?: number
  printMode?: number
}

export type HiTiCurrentResult<T> =
  | { ok: true; data?: T }
  | { ok: false; error: string }

export interface HiTiCurrentErrorCode {
  value: number
  description: string
}

export interface HiTiCurrentPrinterStatus {
  statusValue: number
  statusDescription: string
}
