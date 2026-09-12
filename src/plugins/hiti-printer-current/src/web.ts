import { WebPlugin } from '@capacitor/core'
import type { HiTiPrinterCurrentPlugin, HiTiCurrentResult } from './definitions'

/**
 * path-current 的 Web fallback。HiTi SDK 仅在 Android + HiTi USB printer 可用，
 * web 永远报 "not available"。
 */
export class HiTiPrinterCurrentWeb extends WebPlugin implements HiTiPrinterCurrentPlugin {
  private unavailable(): Promise<HiTiCurrentResult<never>> {
    return Promise.resolve({
      ok: false,
      error: 'HiTiPrinterCurrent is only available on Android with a connected HiTi USB photo printer.',
    })
  }
  startService(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  stopService(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getPrinterStatus(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getModelName(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getSerialNumber(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getFirmwareVersion(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getRibbonInfo(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  getPrintCount(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  resetPrinter(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  resumeJob(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  ejectPaperJam(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  printPhoto(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  /** 开发期临时预览通道，web 端无可用实现 */
  composePrintOverlay(): Promise<HiTiCurrentResult<never>> { return this.unavailable() }
  async startLogSession(): Promise<HiTiCurrentResult<{ path: string }>> { return { ok: true, data: { path: '' } } }
  async closeLogSession(): Promise<HiTiCurrentResult<string>> { return { ok: true, data: '' } }
  async captureLog(): Promise<HiTiCurrentResult<string>> { return { ok: true, data: '' } }
  async initForPage(): Promise<HiTiCurrentResult<string>> { return { ok: true, data: 'web_noop' } }
  async releaseForPage(): Promise<HiTiCurrentResult<string>> { return { ok: true, data: 'web_noop' } }
}
