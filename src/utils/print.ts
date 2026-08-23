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
 * HiTi 4×6 打印输出规范（landscape 6×4 inch）。
 *
 * HiTi SDK 不会按 PaperSize 自动按比例适配输入图——它把 bitmap center-crop 到
 * 当前 PaperSize 的物理像素。如果输入图比例 = 6:4 landscape 就会刚刚好顶满；
 * 比例不一致的图，要么被裁掉主体（portrait 输进 landscape 纸）要么周围留白
 *（landscape 输进 portrait 纸）。
 *
 * 这里在 TS 端先做一次 normalization：把任意比例/分辨率的输入图，统一处理成
 * 1536×1024 (3:2 = 6:4 landscape) JPEG 喂给 native。这样不管调用方塞进来
 * 的是手机拍的竖版 1080×1920、相机横版 4032×3024、还是带圆形 logo 的合成图，
 * 最终打出来的方向都是 6×4 landscape 顶满（中心 crop），与 paperType=2
 * (PAPER_SIZE_6X4_PHOTO) 完全吻合。
 *
 * 为什么不直接选 SDK 最大像素（1844×1240）？
 * - 1536×1024 已经够 6×4 @ 256 dpi，肉眼清晰足够
 * - HiTi SDK 对超大 bitmap 解码会慢一截（带颜色表查表），1536×1024 居中
 * - 同一张照片传到 SD 卡/相册的体积更小，Wi-Fi 链路更短
 */
const PAPER_W = 1536
const PAPER_H = 1024 // 3:2 = 6:4 landscape
const PAPER_JPEG_QUALITY = 0.92

/**
 * 加载 input dataURL / URL / path 为 HTMLImageElement。
 * 用 `decode()` 而不是 onload，明确等解码完成（特别是大图，避免画到
 * canvas 时还未就绪导致 blank bitmap）。
 */
async function loadImage(input: string): Promise<HTMLImageElement> {
  const dataUrl = await resolveImageAsDataUrl(input)
  if (!dataUrl) throw new Error('fitImageToPaper: empty image source')

  const img = new Image()
  img.decoding = 'async'
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error(`fitImageToPaper: image failed to decode (src prefix="${dataUrl.slice(0, 40)}")`))
    img.src = dataUrl
  })
  try {
    await img.decode()
  } catch (e) {
    // decode() 在某些 base64 data URL 上不稳定；onload 已通过，继续走
    console.warn('[print/fit] img.decode() rejected, continuing on onload:', e)
  }
  return img
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
 * Fit an arbitrary image into the HiTi 4×6 landscape paper by center-cropping
 * (a.k.a. "cover fit" / "fill"). The result is a {@code PAPER_W × PAPER_H}
 * JPEG dataURL whose aspect ratio exactly matches
 * {@code PaperSize.PAPER_SIZE_6X4_PHOTO} (paperType=2).
 *
 * <p><b>为什么是 center-crop 而不是 letterbox？</b>
 * <ul>
 *   <li>当前观察到的现象是"打印方向对，照片未能占满整个打印纸"——表明需要
 *       cover 模式让图撑满纸。</li>
 *   <li>HiTi SDK 拿到 bitmap 后会按 PaperSize 物理像素再做一次 center-crop
 *       居中，如果输入图比例 = 6:4 就正好对齐；不一致就会再次被 SDK 二级
 *       裁切/留白。我们这里在 TS 端提前 normalize 到 1536×1024 (3:2 = 6:4)
 *       等同于把二级 crop 取消掉。</li>
 *   <li>中心 crop 对人像类照片最安全（主体通常在中央）。如果以后要避开头顶
 *       /下巴等特殊部位，可以再加 face detection / smart crop。</li>
 * </ul>
 *
 * <p><b>流程：</b>
 * <ol>
 *   <li>把输入图（dataURL / URL / path）解析为 HTMLImageElement</li>
 *   <li>计算"cover 到 PAPER_W × PAPER_H"的源矩形 sx/sy/sw/sh（等比放大，
 *       长边顶满）</li>
 *   <li>创建 PAPER_W × PAPER_H canvas，白底</li>
 *   <li>drawImage 把源矩形画到画布 (0,0)-(PAPER_W,PAPER_H)</li>
 *   <li>导出 JPEG，quality = PAPER_JPEG_QUALITY</li>
 *   <li>返回带 data:image/jpeg;base64, 前缀的 dataURL</li>
 * </ol>
 *
 * @returns {Promise<string>} JPEG dataURL，always data: scheme, never raw base64
 * @throws {Error} 图像 decode 失败 / canvas 不可用 / 导出空数据
 */
export async function fitImageToPaper(input: string): Promise<string> {
  if (!input) throw new Error('fitImageToPaper: input is empty')
  console.log('[print/fit] start, PAPER=' + PAPER_W + 'x' + PAPER_H + ' input prefix=' + input.slice(0, 40))

  const img = await loadImage(input)
  const srcW = img.naturalWidth || img.width
  const srcH = img.naturalHeight || img.height
  if (!srcW || !srcH) throw new Error(`fitImageToPaper: image has zero dimensions (${srcW}x${srcH})`)

  // 等比放大 + center-crop：求 cover scale（取较大者）和源矩形
  const targetRatio = PAPER_W / PAPER_H // 1.5  → landscape
  const srcRatio = srcW / srcH
  let sx: number, sy: number, sw: number, sh: number
  if (srcRatio > targetRatio) {
    // 原图比目标更"宽"——按高度对齐，左右两边各裁掉一些
    sh = srcH
    sw = srcH * targetRatio
    sx = (srcW - sw) / 2
    sy = 0
  } else {
    // 原图比目标更"高"——按宽度对齐，上下两边各裁掉一些
    sw = srcW
    sh = srcW / targetRatio
    sx = 0
    sy = (srcH - sh) / 2
  }
  console.log('[print/fit] src=' + srcW + 'x' + srcH + ' ratio=' + srcRatio.toFixed(3)
    + ' targetRatio=' + targetRatio.toFixed(3)
    + ' coverRect=(' + Math.round(sx) + ',' + Math.round(sy) + ',' + Math.round(sw) + ',' + Math.round(sh) + ')')

  // 创建画布：白底
  const canvas = document.createElement('canvas')
  canvas.width = PAPER_W
  canvas.height = PAPER_H
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('fitImageToPaper: 2d context unavailable')

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, PAPER_W, PAPER_H)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, sx, sy, sw, sh, 0, 0, PAPER_W, PAPER_H)

  const dataUrl: string = await new Promise((resolve, reject) => {
    try {
      const out = canvas.toDataURL('image/jpeg', PAPER_JPEG_QUALITY)
      if (!out || out === 'data:,') reject(new Error('fitImageToPaper: toDataURL returned empty'))
      else resolve(out)
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
  console.log('[print/fit] done, dataUrlLen=' + dataUrl.length
    + ' head=' + dataUrl.slice(0, 40))

  // 让 gc 回收 img
  img.src = ''
  return dataUrl
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
        // 直接 fit 到 HiTi 4×6 landscape paper (1536×1024, 3:2)。
        // 不做 normalize，SDK 会自己 center-crop，留白/裁切不可控。
        // fitImageToPaper 内部已经处理 URL → dataURL → image decode → cover-fit → JPEG；
        // 这里是源头，不需要再单独 resolve。
        const resolved = await fitImageToPaper(opts.goodImgUrl)
        console.log('[print/HiTi] fitted goodImgUrl:', {
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
