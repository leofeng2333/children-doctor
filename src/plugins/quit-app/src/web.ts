import { WebPlugin } from '@capacitor/core'
import type { QuitAppPlugin } from './definitions'

/**
 * Web 端的 QuitApp 实现。
 *
 * 浏览器出于安全考虑不允许脚本关闭非脚本打开的标签页，
 * 所以这里直接 resolve；只在调用方加一行 log 提示。
 * 真实退出行为只在 Android 原生上发生。
 */
export class QuitAppWeb extends WebPlugin implements QuitAppPlugin {
  async exitApp(): Promise<void> {
    // eslint-disable-next-line no-console
    console.log('[QuitApp] exitApp called on web — ignored (browser does not allow script to close window).')
    try {
      window.close()
    } catch {
      // noop
    }
  }
}
