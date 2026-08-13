import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'
import { HiTiPrinter } from '@/plugins/hiti-printer'

export interface PrintOptions {
  /** 主图 URL（good-img / 静态 success 图） */
  goodImgUrl: string
  /**
   * 公众号二维码的 PNG dataURL（已废弃，仅为兼容旧调用方保留）。
   * HiTi 协议不渲染 HTML 二维码，传入也会被丢弃。如果未来要重新支持
   * 二维码打印，请走系统 PrintManager 或新写一条 HiTi overlay 路径。
   */
  qrcodeUrl?: string
  /** 打印任务名（显示在 HiTi native log / 调用方业务日志） */
  jobName?: string
}

/**
 * Resolves an image URL into a base64 dataURL.
 *
 * Inputs that are already `data:` URLs are returned unchanged. `http(s)://`
 * URLs and `file://` paths are fetched (web) or read via the native
 * DualCamera plugin (Android) and converted to base64.
 */
async function resolveImageAsDataUrl(input: string): Promise<string> {
  if (!input) return ''
  if (input.startsWith('data:')) {
    console.log('[print/resolve] input already data URL, len=' + input.length)
    return input
  }

  const isWeb = Capacitor.getPlatform() === 'web'
  console.log('[print/resolve] input needs conversion: isWeb=' + isWeb + ' prefix=' + input.slice(0, 30))

  if (isWeb) {
    if (input.startsWith('http://') || input.startsWith('https://') || input.startsWith('file://')) {
      const res = await fetch(input)
      if (!res.ok) throw new Error(`Failed to fetch image: HTTP ${res.status}`)
      const blob = await res.blob()
      const reader = new FileReader()
      const dataUrl: string = await new Promise((resolve, reject) => {
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'))
        reader.readAsDataURL(blob)
      })
      console.log('[print/resolve] web fetch OK, dataUrl len=' + dataUrl.length)
      return dataUrl
    }
    // Bare local path on web: not supported.
    throw new Error(`resolveImageAsDataUrl: unsupported input "${input}"`)
  }

  // Native: route through the DualCamera plugin which has privileged
  // access to ContentResolver / app cache directories.
  console.log('[print/resolve] native path calling DualCamera.readImageAsBase64, input=' + input)
  const { base64 } = await DualCamera.readImageAsBase64({ input })
  console.log('[print/resolve] DualCamera.readImageAsBase64 OK, base64 len=' + base64.length)
  return `data:image/jpeg;base64,${base64}`
}

/**
 * HiTi 专用打印入口：把 6 寸照片直接送进 HiTi USB 打印机。
 *
 * 行为对齐 HiTi SDK 自带 sampleAPK MainActivity#operatePrinter(USB_PRINT_PHOTOS)：
 * - 不走 WebView / PrintManager，直接用 SDK 协议发到 USB
 * - 不渲染 HTML 模板，HiTi 期望的是裸 JPEG + PaperSize
 * - 二维码字段 {@link PrintOptions.qrcodeUrl} 被忽略（HiTi 协议不支持 HTML 排版）
 *
 * @throws 当 SDK 不可用 / 打印机未连接 / 发送失败时，抛 Error
 */
export async function printPhoto(opts: PrintOptions & { paperType?: number }): Promise<void> {
  if (!opts.goodImgUrl) throw new Error('goodImgUrl is required')

  const paperType = opts.paperType ?? 2
  console.log('[print/HiTi] ====== HiTi 打印开始 ======')
  console.log('[print/HiTi] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    jobName: opts.jobName,
    paperType,
  })

  // 启动 native 端日志会话：本次 HiTi 打印期间所有 native 日志（logcat + 文件）
  // 都汇入 print_logs/print_<timestamp>.log。失败也不抛错（文件失败不影响打印）。
  let nativeLogPath = ''
  try {
    const session = await HiTiPrinter.startLogSession()
    if (session.ok && session.data?.path) {
      nativeLogPath = session.data.path
      console.log('[print/HiTi] native log session started:', nativeLogPath)
    } else {
      console.warn('[print/HiTi] startLogSession failed:', session.ok ? '(empty path)' : session.error)
    }
  } catch (e) {
    console.warn('[print/HiTi] startLogSession threw:', e)
  }

  // Mirror 这一行 JS 端日志到 native 文件（即使后面抛错也会被 close 写入）
  try {
    await HiTiPrinter.captureLog({ tag: 'TS', msg: `printPhoto start, paperType=${paperType} nativeLog=${nativeLogPath}` })
  } catch {}

  try {
    // ★双保险兜底：除了 native 端 PRINT_PHOTO_TIMEOUT_SECONDS 的 future.get(timeout)，
    // 这里再加一层 TS Promise.race，避免 native 端兜底编译失败 / 漏触发时 UI 永久卡在"打印中"。
    // 25s 比 native 的 20s 多 5s，确保正常情况下是 native 先回报错或成功。
    await Promise.race([
      (async () => {
        const resolved = await resolveImageAsDataUrl(opts.goodImgUrl)
        console.log('[print/HiTi] resolved goodImgUrl:', {
          isDataUrl: resolved.startsWith('data:'),
          length: resolved.length,
          prefix: resolved.slice(0, 40),
        })
        const comma = resolved.indexOf(',')
        const base64 = comma >= 0 ? resolved.slice(comma + 1) : resolved
        if (!base64) throw new Error('goodImgUrl 解析为空')

        // HiTi SDK 要求所有 USB op 之前必须先 StartService，否则 USB_CHECK_PRINTER_STATUS
        // 之类直接返回 "Service is not start"。
        console.log('[print/HiTi] step 1/2: startService...')
        const startRes = await HiTiPrinter.startService()
        console.log('[print/HiTi] startService result:', startRes)
        if (!startRes.ok) throw new Error(`HiTi service 启动失败：${startRes.error}`)

        // 不做 getPrinterStatus 预检：HiTi SDK 没有不调 USB transfer 的轻量探测，
        // 预检本身会再发一次 USB_CHECK_PRINTER_STATUS 卡住直到 native 兜底超时，
        // 体感上跟直接打一样卡，而且错误信息被预检吃掉一层更难看。
        console.log('[print/HiTi] skipping getPrinterStatus pre-check, going straight to print')

        // 发打印任务（让 Java 侧自己把 base64 写盘，避免引入 Filesystem 插件）
        console.log('[print/HiTi] step 2/2: printPhoto...', { paperType })
        const printRes = await HiTiPrinter.printPhoto({
          base64,
          paperType,
        })
        console.log('[print/HiTi] printPhoto result:', printRes)
        if (!printRes.ok) throw new Error(`HiTi 打印失败：${printRes.error}`)
      })(),
      new Promise<never>((_, reject) =>
        setTimeout(
          () => reject(new Error('[TS-watchdog] HiTi print did not resolve within 25s (likely SDK stuck)')),
          25_000,
        ),
      ),
    ])
    console.log('[print/HiTi] ====== HiTi 打印完成 ======')
  } catch (hitiErr) {
    const msg = hitiErr instanceof Error ? hitiErr.message : String(hitiErr)
    const stack = hitiErr instanceof Error ? hitiErr.stack : undefined
    console.warn('[print/HiTi] HiTi failed:', msg)
    if (stack) console.warn('[print/HiTi] HiTi stack:', stack)
    throw new Error(`HiTi failed: ${msg}`)
  } finally {
    // 不管成功失败都关闭 native 日志会话（写 footer）
    try {
      await HiTiPrinter.closeLogSession()
      console.log('[print/HiTi] native log session closed:', nativeLogPath)
    } catch (e) {
      console.warn('[print/HiTi] closeLogSession failed:', e)
    }
  }
}
