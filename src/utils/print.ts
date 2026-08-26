import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'
import { HiTiPrinter } from '@/plugins/hiti-printer'

/**
 * 页面级 HiTi USB 占用：调用时机与具体组件生命周期耦合，详见
 * {@link #printPageMounted} / {@link #printPageUnmounted}。
 *
 * <p>背景：HiTi SDK 启动后会立刻通过 attach 监听主动 claim 自己的 USB interface
 * (VID=0x0D16)，如果 app 启动时就 init，会导致同一 USB controller 上的 UVC camera
 * 在 Camera2 openCamera 时拿到 ERROR_IN_USE，预览起不来。修复策略：
 * HiTi 不在 app 启动时 init，只在打印相关页面（ScanSubscription / PrintTestView）
 * 进入前 init，离开后 release。这样其他页面（特别是 Camera2 预览）期间 HiTi
 * 不占 USB，UVC camera 永远拿到独占 access。
 */

/**
 * 打印页面 mount hook。PrintTestView / DetailAnalysisView 挂载时调一次。
 *
 * <p>注意：必须 await 完成后再开始用 HiTi（startService / printPhoto），
 * 因为 init 内部执行 ServiceConnector.register 是异步的（main looper post）。
 */
export async function printPageMounted(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  console.log('[print/port] print page mounted, init HiTi (bind service + claim USB interface)')
  try {
    const r = await HiTiPrinter.initForPage()
    console.log('[print/port] initForPage result:', r)
  } catch (e) {
    console.warn('[print/port] initForPage threw:', e)
  }
}

/**
 * 打印页面 unmount hook。PrintTestView / DetailAnalysisView 卸载时调一次。
 * release 后 HiTi 完全不占 USB，Camera2 / 其他 USB 设备可以无障碍使用 USB bus。
 */
export async function printPageUnmounted(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  console.log('[print/port] print page unmounted, release HiTi (unbind service + release USB interface)')
  try {
    const r = await HiTiPrinter.releaseForPage()
    console.log('[print/port] releaseForPage result:', r)
  } catch (e) {
    console.warn('[print/port] releaseForPage threw:', e)
  }
}

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
  /**
   * 打印模式（仅用于 PrintTestView 多模式对比诊断）：
   *
   * - 'current'（默认）：TS letterbox + Java 不旋转（与 v1.0.15-print-stable 一致，
   *   生产路径）；portrait 输入会输出 portrait bitmap 给 SDK —— 实测可能 25s 卡死
   *
   * - 'cover-fit'：TS 强制 cover-fit 到 1536×1024 landscape；Java 不旋转
   *   —— 验证 "TS letterbox 输出 portrait bitmap 是不是 25s 卡死元凶"
   *   若 cover-fit 模式下 direct-print 恢复正常 ⇒ TS letterbox 是根因
   *
   * - 'portrait-rotate'：TS letterbox + Java 检测 portrait bitmap 后 90° 旋转
   *   —— 验证 "Java 端加 portrait 旋转能否修复"
   *
   * - 'portrait-rotate-normalize'：TS letterbox + Java portrait 旋转 + normalize crop
   *   —— 510e621 时的混合方案，最完整的归一化
   */
  printMode?: 'current' | 'cover-fit' | 'portrait-rotate' | 'portrait-rotate-normalize'
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
 * 1844×1240 (6:4 landscape) JPEG 喂给 native。这样不管调用方塞进来
 * 的是手机拍的竖版 1080×1920、相机横版 4032×3024、还是带圆形 logo 的合成图，
 * 最终打出来的方向都是 6×4 landscape 顶满（中心 crop），与 paperType=2
 * (PAPER_SIZE_6X4_PHOTO) 完全吻合。
 *
 * <p>输出物理像素：1844×1240（SDK 注释里 "Paper size / photo pixels match table"
 * 标注 PAPER_SIZE_6X4_PHOTO 期望 1844×1240），命中 SDK 内部的 fast path。
 *
 * <p>实测在 rockchip / SDK 34 上 1536×1024 输入会触发 doService 偶发卡死
 * （见 print_logs/print_20260825_082743_517.log + print_20260825_083054_406.log：
 * log 停在 "calling serviceConnector.doService synchronously"，同时 ClockTask
 * 持续 errCode=0x0——USB 通道健康但 USB_PRINT_PHOTOS 内部分支出问题）。
 *
 * <p>bitmap 翻大后 base64 长度增加（≈ 320KB vs ≈ 130KB），但一次打印 5~15秒，
 * 相比"卡 25 秒然后失败"用户体验反而更好。
 */
const PAPER_W = 1844
const PAPER_H = 1240 // 6:4 landscape (SDK PAPER_SIZE_6X4_PHOTO expected pixels)
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
 * @param mode 处理模式（仅诊断对比）：
 *   - 'cover-fit'：强制 cover-fit 到 1536×1024 landscape（裁切 portrait 上下或 landscape 左右）
 *   - 其它值：letterbox（portrait 输入保持竖向，宽度方向两边留白）
 * @returns {Promise<string>} JPEG dataURL，always data: scheme, never raw base64
 * @throws {Error} 图像 decode 失败 / canvas 不可用 / 导出空数据
 */
export async function fitImageToPaper(input: string, mode: 'cover-fit' | 'letterbox' = 'letterbox'): Promise<string> {
  if (!input) throw new Error('fitImageToPaper: input is empty')
  console.log('[print/fit] start, PAPER=' + PAPER_W + 'x' + PAPER_H + ' input prefix=' + input.slice(0, 40))

  const img = await loadImage(input)
  const srcW = img.naturalWidth || img.width
  const srcH = img.naturalHeight || img.height
  if (!srcW || !srcH) throw new Error(`fitImageToPaper: image has zero dimensions (${srcW}x${srcH})`)

  // Letterbox 模式：保持原图方向，不做 cover-fit 裁切。
  //
  // cover-fit 问题：portrait 输入（竖图，人脸在上半部分）按宽度对齐后，
  // canvas 上下各裁掉 (srcH - sh)/2，只保留中间一条横带。
  // 这条横带里的人脸被拦腰截断，进 SDK 后方向也变成横的，打印出来是横的。
  //
  // letterbox 做法：portrait 输入时把完整图像居中画到 canvas，两边留白。
  // 白色背景保证留白区域可打印。Java 端检测 portrait bitmap 后会旋转 90°
  // 变成 landscape，再 normalize 填满纸张 —— 这样竖图能正确竖着打印。
  const targetRatio = PAPER_W / PAPER_H // 1.5  → landscape
  const srcRatio = srcW / srcH
  let sx: number, sy: number, sw: number, sh: number
  let destW = PAPER_W
  let destH = PAPER_H
  if (mode === 'cover-fit') {
    // 强制 cover-fit：长边对齐 paper 长边，多余部分裁掉。
    // portrait 输入 → 上下裁切（保留中间横带）；landscape 输入 → 左右裁切。
    // 输出 canvas 永远是 1536×1024 landscape bitmap，喂给 SDK 后走 landscape
    // 处理路径。诊断目的：验证"SDK 收到 portrait bitmap 是否卡死"。
    if (srcRatio > targetRatio) {
      sh = srcH
      sw = srcH * targetRatio
      sx = (srcW - sw) / 2
      sy = 0
    } else {
      sw = srcW
      sh = srcW / targetRatio
      sx = 0
      sy = (srcH - sh) / 2
    }
  } else if (srcRatio > targetRatio) {
    // landscape 输入（宽 > 高）—— 按高度对齐，左右各裁一点（cover-fit）
    sh = srcH
    sw = srcH * targetRatio
    sx = (srcW - sw) / 2
    sy = 0
  } else {
    // portrait 输入（高 > 宽）—— 按宽度缩放，居中画到 canvas，两边留白
    sw = srcW
    sh = srcH
    sx = 0
    sy = 0
    // 竖图宽度按比例映射到 canvas 高度，宽度方向两边留白
    destH = Math.round(srcH * (PAPER_W / srcW))
    destW = PAPER_W
  }
  console.log('[print/fit] mode=' + mode + ' src=' + srcW + 'x' + srcH + ' ratio=' + srcRatio.toFixed(3)
    + ' targetRatio=' + targetRatio.toFixed(3)
    + ' srcRect=(' + Math.round(sx) + ',' + Math.round(sy) + ',' + Math.round(sw) + ',' + Math.round(sh) + ')'
    + ' destCanvas=' + destW + 'x' + destH)

  // 创建画布：白底
  const canvas = document.createElement('canvas')
  canvas.width = destW
  canvas.height = destH
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('fitImageToPaper: 2d context unavailable')

  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, destW, destH)
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  // portrait：居中画；landscape：填满
  if (srcRatio <= targetRatio) {
    // portrait：图像填满 destH，水平居中
    const drawW = destH * srcRatio
    const drawX = (destW - drawW) / 2
    ctx.drawImage(img, sx, sy, sw, sh, drawX, 0, drawW, destH)
  } else {
    // landscape：填满整个 canvas
    ctx.drawImage(img, sx, sy, sw, sh, 0, 0, destW, destH)
  }

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
  // printMode 是 PrintTestView 多模式对比用的诊断字段。生产路径（业务调用方
  // 如 ScanSubscription）不传，落到 'current' 默认值，与 v1.0.15-print-stable
  // 行为完全一致。
  const printMode = opts.printMode ?? 'current'
  console.log('[print/HiTi] ====== HiTi 打印开始 ======')
  console.log('[print/HiTi] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    jobName: opts.jobName,
    paperType,
    printMode,
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
    await HiTiPrinter.captureLog({ tag: 'TS', msg: `printPhoto start, paperType=${paperType} printMode=${printMode} nativeLog=${nativeLogPath}` })
  } catch {}

  try {
    // ★双保险兜底：除了 native 端 PRINT_PHOTO_TIMEOUT_SECONDS 的 future.get(timeout)，
    // 这里再加一层 TS Promise.race，避免 native 端兜底编译失败 / 漏触发时 UI 永久卡在"打印中"。
    // 25s 比 native 的 20s 多 5s，确保正常情况下是 native 先回报错或成功。
    await Promise.race([
      (async () => {
        // 把原图按 printMode 处理：
        // - 'cover-fit'：强制 cover-fit 到 1536×1024 landscape
        // - 其它（'current' / 'portrait-rotate' / 'portrait-rotate-normalize'）：letterbox
        // fitImageToPaper 内部已经处理 URL → dataURL → image decode → drawImage → JPEG；
        // 这里是源头，不需要再单独 resolve。
        const fitMode = printMode === 'cover-fit' ? 'cover-fit' : 'letterbox'
        const resolved = await fitImageToPaper(opts.goodImgUrl, fitMode)
        console.log('[print/HiTi] fitted goodImgUrl:', {
          isDataUrl: resolved.startsWith('data:'),
          length: resolved.length,
          prefix: resolved.slice(0, 40),
        })
        // Mirror 到 native log：fit 输出尺寸判断端口（PNG/JPEG header 字节数估计）
        try {
          await HiTiPrinter.captureLog({
            tag: 'TS',
            msg: `fitImageToPaper(${fitMode}) done, dataUrlLen=${resolved.length} prefix=${resolved.slice(0, 40)}`,
          })
        } catch {}
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
        console.log('[print/HiTi] step 2/2: printPhoto...', { paperType, bitmapProcessMode: printMode })
        const printRes = await HiTiPrinter.printPhoto({
          base64,
          paperType,
          // 把 printMode 也传给 native，让 Java 端按 mode 决定 portrait 旋转 + normalize。
          bitmapProcessMode: printMode,
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
