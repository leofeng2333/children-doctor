import { Capacitor } from '@capacitor/core'
import { HiTiPrinterCurrent } from '@/plugins/hiti-printer-current'

/**
 * HiTi USB 占用生命周期管理（router 守卫 + app 启动入口共用）。
 *
 * <p>app 全程统一使用 {@link HiTiPrinterCurrent}（path-current 新版 plugin），
 * 唯一一份 ServiceConnector 监听 USB attach/detach —— 不再有 legacy / current 之
 * 分，router 守卫不需要做 plugin 切换。
 *
 * <p>状态机（router.beforeEach 触发）：
 * <pre>
 *   to=capture     → release(current)
 *   from=capture   → init(current)
 *   from=print     → release(current)
 *   to=print       → noop（HiTi 已经在 init 状态；app 启动 init 一次就够）
 *   普通路由相互切换 → noop
 * </pre>
 *
 * <p>幂等性：initForPage / releaseForPage 在 native 端都是幂等的（serviceConnector 为
 * null 时第二次 release 是 no-op），所以多次调用是安全的。
 */

/** App 启动 init HiTi 一次（HiTi 默认占 USB）。 */
export async function initHiti(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  try {
    const r = await HiTiPrinterCurrent.initForPage()
    console.log('[print/lifecycle] init HiTi result:', r)
  } catch (e) {
    console.warn('[print/lifecycle] init HiTi threw:', e)
  }
}

/** Release HiTi USB（让 UVC camera 等其它设备独占 bus）。 */
export async function releaseHiti(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  try {
    const r = await HiTiPrinterCurrent.releaseForPage()
    console.log('[print/lifecycle] release HiTi result:', r)
  } catch (e) {
    console.warn('[print/lifecycle] release HiTi threw:', e)
  }
}
