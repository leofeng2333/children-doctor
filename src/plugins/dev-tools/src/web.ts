import { WebPlugin } from '@capacitor/core'
import type { DevToolsDialogPlugin, DevToolsDialogResult } from './definitions'

/**
 * Web 端 stub：开发者工具弹窗只用于 Android 设备调参，浏览器上不可用。
 * 如果不小心在 web 平台调用会直接抛错。
 */
export class DevToolsDialogWeb extends WebPlugin implements DevToolsDialogPlugin {
  async show(): Promise<DevToolsDialogResult> {
    throw this.unavailable(
      'DevToolsDialog is only available on Android. Use the existing DevToolsDialog.vue ' +
        '(web fallback) when running in the browser.',
    )
  }
}
