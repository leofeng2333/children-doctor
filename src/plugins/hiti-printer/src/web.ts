import { WebPlugin } from '@capacitor/core'
import type { HiTiPrinterPlugin, HiTiResult } from './definitions'

/**
 * Web fallback for {@link HiTiPrinterPlugin}. The HiTi SDK only runs on a
 * connected HiTi USB photo printer (Android device with USB-Host + HiTi
 * firmware), so the web fallback always reports "not available" with a
 * recognizable error string.
 */
export class HiTiPrinterWeb extends WebPlugin implements HiTiPrinterPlugin {
  private unavailable(): Promise<HiTiResult<never>> {
    return Promise.resolve({
      ok: false,
      error: 'HiTiPrinter is only available on Android with a connected HiTi USB photo printer.',
    })
  }

  startService(): Promise<HiTiResult<never>> { return this.unavailable() }
  stopService(): Promise<HiTiResult<never>> { return this.unavailable() }
  getPrinterStatus(): Promise<HiTiResult<never>> { return this.unavailable() }
  getModelName(): Promise<HiTiResult<never>> { return this.unavailable() }
  getSerialNumber(): Promise<HiTiResult<never>> { return this.unavailable() }
  getFirmwareVersion(): Promise<HiTiResult<never>> { return this.unavailable() }
  getRibbonInfo(): Promise<HiTiResult<never>> { return this.unavailable() }
  getPrintCount(): Promise<HiTiResult<never>> { return this.unavailable() }
  resetPrinter(): Promise<HiTiResult<never>> { return this.unavailable() }
  resumeJob(): Promise<HiTiResult<never>> { return this.unavailable() }
  ejectPaperJam(): Promise<HiTiResult<never>> { return this.unavailable() }
  printPhoto(): Promise<HiTiResult<never>> { return this.unavailable() }

  /**
   * Web fallback: no native log file. Return ok with empty path so the TS
   * caller's flow continues normally (logs will simply be skipped on web).
   */
  async startLogSession(): Promise<HiTiResult<{ path: string }>> {
    return { ok: true, data: { path: '' } }
  }
  async closeLogSession(): Promise<HiTiResult<string>> {
    return { ok: true, data: '' }
  }
  async captureLog(): Promise<HiTiResult<string>> {
    return { ok: true, data: '' }
  }
}