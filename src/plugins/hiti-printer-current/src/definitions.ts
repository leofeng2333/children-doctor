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
  /**
   * 临时预览通道（仅供开发期确认 print overlay 合成效果，未做实际打印）。
   * 返回合成后 JPEG 的 base64 片段（不含 data:image/jpeg;base64, 前缀），
   * 调用方拼成 `<...>;base64,<base64>` dataURL 即可直接作为 img src 显示。
   *
   * <p>⚠️ 当前为开发期临时 debug 入口，确认效果后会移除。
   * 等价于：
   * <ol>
   *   <li>主图 base64 → 临时文件</li>
   *   <li>{@link HiTiPrintOverlayBuilder#composeAndWriteToCache} 跑一次得到 paper-sized 合成图</li>
   *   <li>合成图 → base64 返回</li>
   *   <li>两个临时文件全部删除</li>
   * </ol>
   */
  composePrintOverlay(options: HiTiCurrentComposePrintOverlayOptions): Promise<HiTiCurrentResult<HiTiCurrentComposePrintOverlayData>>
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

export interface HiTiCurrentComposePrintOverlayOptions {
  base64: string
  paperType?: number
}

export interface HiTiCurrentComposePrintOverlayData {
  /** JPEG 字节的 base64 编码（不含 data: 前缀） */
  base64: string
  /** 固定 "image/jpeg" */
  contentType: string
  paperType: number
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
