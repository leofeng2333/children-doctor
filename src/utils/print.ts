import { Printer } from '@capgo/capacitor-printer'
import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'
import { HiTiPrinter } from '@/plugins/hiti-printer'

export interface PrintOptions {
  /** 主图 URL（good-img / 静态 success 图） */
  goodImgUrl: string
  /**
   * 公众号二维码的 PNG dataURL。
   * 由前端根据 VITE_QRCODE_BASE_URL 拼链接，再用 qrcode.toDataURL 转图。
   * 可选：未传或空串时，打印模板只在 footer 区域占位，不渲染二维码图片。
   */
  qrcodeUrl?: string
  /** 打印任务名（显示在系统打印队列） */
  jobName?: string
}

/**
 * Resolves an image URL into a base64 dataURL that the print WebView can
 * always render, regardless of origin.
 *
 * Why: {@link Printer.printHtml} on Android spins up a WebView via
 * `loadDataWithBaseURL(null, ...)` whose origin is "data:" / "about:blank".
 * Such an origin refuses to load `file://` resources (Same-Origin Policy),
 * and `http(s)://` images also fail to load in time before PrintManager
 * snapshots the DOM. Inlining the image as a `data:image/jpeg;base64,...`
 * URL sidesteps both problems.
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
 * 6 寸照片纸规格：4 × 6 in (102 × 152 mm)
 * 1 in = 96 CSS px
 * 主体：上方 goodImg（占 ~80% 高度），底部二维码 + 引导文案（占 ~20%）
 */
function buildPhotoHtml(opts: PrintOptions): string {
  const jobName = opts.jobName ?? '宝贝照片'
  const qrcodeUrl = opts.qrcodeUrl?.trim() ?? ''
  // 无二维码时只保留占位元素，保持 footer 高度不变，文字内容居中显示
  const qrcodeImg = qrcodeUrl
    ? `<img class="qrcode" src="${escapeAttr(qrcodeUrl)}" alt="qrcode" />`
    : `<div class="qrcode qrcode-placeholder" aria-hidden="true"></div>`
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>${escapeHtml(jobName)}</title>
  <style>
    @page {
      size: 4in 6in;
      margin: 0;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    html, body {
      width: 4in;
      height: 6in;
      font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
      color: #1a1a1a;
      background: #ffffff;
    }
    .photo {
      width: 4in;
      height: 6in;
      display: flex;
      flex-direction: column;
      page-break-after: avoid;
      page-break-inside: avoid;
    }
    .photo-main {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 0.12in;
      overflow: hidden;
    }
    .photo-main img {
      max-width: 100%;
      max-height: 100%;
      object-fit: contain;
      display: block;
    }
    .photo-footer {
      display: flex;
      align-items: center;
      gap: 0.18in;
      padding: 0.14in 0.18in;
      border-top: 1px dashed #d9d9d9;
      background: #fafafa;
    }
.photo-footer img.qrcode,
.photo-footer .qrcode-placeholder {
  width: 0.85in;
  height: 0.85in;
  object-fit: contain;
  flex-shrink: 0;
}

/* 占位样式：无二维码时显示一个浅色虚线框 */
.photo-footer .qrcode-placeholder {
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  background: #f5f5f5;
}
    .photo-footer .text {
      flex: 1;
      min-width: 0;
    }
    .photo-footer .text .title {
      font-size: 11pt;
      font-weight: 700;
      margin-bottom: 2pt;
      color: #1a1a1a;
    }
    .photo-footer .text .sub {
      font-size: 8.5pt;
      color: #666;
      line-height: 1.35;
    }
    @media print {
      html, body { width: 4in; height: 6in; }
    }
  </style>
</head>
<body>
  <div class="photo">
    <div class="photo-main">
      <img src="${escapeAttr(opts.goodImgUrl)}" alt="photo" />
    </div>
    <div class="photo-footer">
      ${qrcodeImg}
      <div class="text">
        <div class="title">扫码关注公众号</div>
        <div class="sub">获取宝贝的电子版照片与面型分析报告</div>
      </div>
    </div>
  </div>
</body>
</html>`
}

function escapeHtml(s: string): string {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

function escapeAttr(s: string): string {
  return escapeHtml(s).replace(/"/g, '&quot;')
}

/**
 * 打印 6 寸照片：good-img（主图） + 公众号二维码
 *
 * - Web 平台：调用 window.print()（Capacitor Printer 已自动降级）
 * - Android：@capgo/capacitor-printer.printHtml，弹系统打印对话框
 * - 失败：抛错（不吞），调用方负责提示
 */
export async function printPhotoWithQrcode(opts: PrintOptions): Promise<void> {
  if (!opts.goodImgUrl) {
    throw new Error('goodImgUrl is required')
  }
  console.log('[print/system] ====== System PrintManager 打印开始 ======')
  console.log('[print/system] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    hasQrcodeUrl: !!opts.qrcodeUrl?.trim(),
    jobName: opts.jobName,
    platform: Capacitor.getPlatform(),
  })

  // 在 Android 上把 file:// / http(s):// 转成 data: URL，避免空 origin
  // WebView 拒绝加载本地资源导致打印主图黑屏。
  const [resolvedGoodImg, resolvedQrcode] = await Promise.all([
    resolveImageAsDataUrl(opts.goodImgUrl),
    opts.qrcodeUrl?.trim() ? resolveImageAsDataUrl(opts.qrcodeUrl.trim()) : Promise.resolve(''),
  ])
  console.log('[print/system] resolved images:', {
    goodImgLen: resolvedGoodImg.length,
    qrcodeLen: resolvedQrcode.length,
  })

  const html = buildPhotoHtml({
    ...opts,
    goodImgUrl: resolvedGoodImg,
    qrcodeUrl: resolvedQrcode,
  })
  const jobName = opts.jobName ?? '宝贝照片'
  console.log('[print/system] html built, length=' + html.length + ' jobName=' + jobName)

  // Web 平台 / 浏览器降级
  if (Capacitor.getPlatform() === 'web') {
    console.log('[print/system] web mode: opening window.print()')
    const w = window.open('', '_blank', 'width=820,height=1240')
    if (!w) throw new Error('无法打开打印窗口，请检查浏览器弹窗拦截设置')
    w.document.open()
    w.document.write(html)
    w.document.close()
    // 等待图片加载后再触发 print
    await new Promise<void>((resolve) => {
      if (w.document.readyState === 'complete') resolve()
      else w.addEventListener('load', () => resolve(), { once: true })
    })
    // 给图片一点时间解码
    await new Promise((r) => setTimeout(r, 300))
    w.focus()
    w.print()
    console.log('[print/system] web print() done')
    return
  }

  console.log('[print/system] calling Printer.printHtml...')
  await Printer.printHtml({
    name: jobName,
    html,
  })
  console.log('[print/system] Printer.printHtml resolved, ====== 完成 ======')
}

/**
 * HiTi 专用：把 6 寸照片直接送进 HiTi USB 打印机。
 *
 * 与 {@link printPhotoWithQrcode} 的关键差异：
 * - 不走 WebView / PrintManager，直接用 SDK 协议发到 USB
 * - 不渲染 HTML 模板，HiTi 期望的是裸 JPEG + PaperSize
 * - 二维码被丢弃（HiTi 协议本身不支持 HTML 排版）
 *
 * @throws 当 SDK 不可用 / 打印机未连接 / 发送失败时，抛 Error
 */
export async function printPhotoWithHiTi(opts: PrintOptions & { paperType?: number }): Promise<void> {
  if (!opts.goodImgUrl) throw new Error('goodImgUrl is required')

  console.log('[print/HiTi] ====== HiTi 打印开始 ======')
  console.log('[print/HiTi] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    hasQrcodeUrl: !!opts.qrcodeUrl?.trim(),
    jobName: opts.jobName,
    paperType: opts.paperType ?? 2,
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
    await HiTiPrinter.captureLog({ tag: 'TS', msg: `printPhotoWithHiTi start, nativeLog=${nativeLogPath}` })
  } catch {}

  try {
    // 取 base64（dataURL → base64）。HiTi 路径是独立调用，复用
    // resolveImageAsDataUrl 的同源/跨域处理逻辑避免重复。
    const resolved = await resolveImageAsDataUrl(opts.goodImgUrl)
    console.log('[print/HiTi] resolved goodImgUrl:', {
      isDataUrl: resolved.startsWith('data:'),
      length: resolved.length,
      prefix: resolved.slice(0, 40),
    })
    const comma = resolved.indexOf(',')
    const base64 = comma >= 0 ? resolved.slice(comma + 1) : resolved
    console.log('[print/HiTi] base64:', {
      length: base64.length,
      empty: !base64,
    })
    if (!base64) throw new Error('goodImgUrl 解析为空')

    // 1) 起 service。HiTi SDK 要求所有 USB op 之前必须先 StartService,
    // 否则 USB_CHECK_PRINTER_STATUS 之类直接返回 "Service is not start"。
    console.log('[print/HiTi] step 1/2: startService...')
    const startRes = await HiTiPrinter.startService()
    console.log('[print/HiTi] startService result:', startRes)
    if (!startRes.ok) throw new Error(`HiTi service 启动失败：${startRes.error}`)

    // 不再做 getPrinterStatus 预检：HiTi SDK 没有不调 USB transfer 的轻量探测，
    // 预检本身会再发一次 USB_CHECK_PRINTER_STATUS 卡住直到 native 兜底超时，
    // 体感上跟直接打一样卡，而且错误信息被预检吃掉一层更难看。
    // 直接走打印，错误信息（包含 SDK 真实 errCode + native 兜底超时）会原样透传。
    console.log('[print/HiTi] skipping getPrinterStatus pre-check, going straight to print')

    // 2) 发打印任务（让 Java 侧自己把 base64 写盘，避免引入 Filesystem 插件）
    console.log('[print/HiTi] step 2/2: printPhotoBase64...', {
      paperType: opts.paperType ?? 2,
    })
    const printRes = await HiTiPrinter.printPhotoBase64({
      base64,
      paperType: opts.paperType ?? 2,
    })
    console.log('[print/HiTi] printPhotoBase64 result:', printRes)
    if (!printRes.ok) throw new Error(`HiTi 打印失败：${printRes.error}`)
    console.log('[print/HiTi] ====== HiTi 打印完成 ======')
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

/**
 * SampleAPK 同款 HiTi 打印入口。区别于 {@link printPhotoWithHiTi}：
 * <ol>
 *   <li>打印调用走 {@link HiTiPrinter.printPhotoSample}（对应 native
 *       {@code HiTiPrinterManager#printPhotoSample}），native 侧在 raw
 *       {@code new Thread()} 上同步 {@code serviceConnector.doService(job)}，
 *       <b>不在 io executor 里串行化</b>，因此和原有 HiTi 路径可以真正并行。
 *       doService 完成后立即把 {@code serviceConnector.m_strTablesRoot}
 *       清空回 {@code ""}，行为对齐 sample MainActivity 行 651-655。</li>
 *   <li>所有 MATTE/PRINTCOUNT/PRINTMODE/PaperType 都由 TS 层显式
 *       传入，默认值与 sample MainActivity 一致：MATTE=1（覆膜）、
 *       PRINTCOUNT=1、PRINTMODE=0（standard）、PaperType=2（4x6）。
 *       注意 MATTE=1（覆膜）与 {@link printPhotoWithHiTi}
 *       内 {@code buildPhotoAttr} 硬编码的 MATTE=0（不覆膜）有实际差异。</li>
 *   <li>返回值：成功时 {@code data} 是 native {@code retrieveSampleData}
 *       拼出来的字符串 {@code "<<<USB_PRINT_PHOTOS -ID<id> : err <0x<hex> <desc>>"}，
 *       失败时 {@code error} 同样会被填上这段字符串以便前端展示。</li>
 *   <li>日志分层：JS 前缀 {@code [print/sampleHiTi]}；native 侧统一前缀
 *       {@code [sample]} 与原 {@code printPhoto} 的无 tag 区分。</li>
 *   <li>不预先做 {@code startService} / {@code getPrinterStatus} 探活——
 *       sample 原版把这两步拆成 {@code b_startService} 按钮单独跑，{@code b_printPhoto}
 *       里只调 {@code operatePrinter(USB_PRINT_PHOTOS)}。因此本入口信任调用方
 *       已经把 Service 起好，否则让 native 端 ErrorCode 自己炸出来。</li>
 * </ol>
 *
 * 二维码仍然被丢弃（HiTi 协议不渲染 HTML），与 {@link printPhotoWithHiTi} 一致。
 *
 * @throws SDK 不可用 / 打印机未连接 / 发送失败时抛 Error；Error.message 会包含
 *         native 侧的 retrieveSampleData 字符串，便于排查。
 */
export async function printPhotoWithSampleHiTi(opts: PrintOptions & {
  paperType?: number
  printCount?: number
  matte?: number
  printMode?: number
}): Promise<void> {
  if (!opts.goodImgUrl) throw new Error('goodImgUrl is required')

  // 与 sample MainActivity 完全一致的默认值。允许覆盖但默认贴合 sample。
  const paperType = opts.paperType ?? 2
  const printCount = opts.printCount ?? 1
  const matte = opts.matte ?? 1
  const printMode = opts.printMode ?? 0

  console.log('[print/sampleHiTi] ====== SampleAPK-style HiTi print 开始 ======')
  console.log('[print/sampleHiTi] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    hasQrcodeUrl: !!opts.qrcodeUrl?.trim(),
    jobName: opts.jobName,
    paperType,
    printCount,
    matte,
    printMode,
    // 与 printPhotoWithHiTi 区分的关键差异：
    threadModel: 'raw new Thread (NOT io executor)',
    m_strTablesRootPolicy: 'set → doService → reset to "" (sample 行 651-655)',
    matte_drift: 'sample 默认 1 (覆膜); printPhotoWithHiTi 硬编码 0 (不覆膜)',
  })

  // 1) 独立会话：日志写到 sample_print_<timestamp>.log，路径上区别于 HiTi 路径。
  let nativeLogPath = ''
  try {
    const session = await HiTiPrinter.startLogSession()
    if (session.ok && session.data?.path) {
      nativeLogPath = session.data.path
      console.log('[print/sampleHiTi] native log session started (tagged [sample]):', nativeLogPath)
    } else {
      console.warn('[print/sampleHiTi] startLogSession failed:', session.ok ? '(empty path)' : session.error)
    }
  } catch (e) {
    console.warn('[print/sampleHiTi] startLogSession threw:', e)
  }

  try {
    await HiTiPrinter.captureLog({
      tag: 'SAMPLE_JS',
      msg: `printPhotoWithSampleHiTi start, thread=raw-paperType=${paperType} printCount=${printCount} matte=${matte} printMode=${printMode} nativeLog=${nativeLogPath}`,
    })
  } catch {}

  try {
    // 2) 解码图片 → base64（与 printPhotoWithHiTi 同源处理逻辑）
    const resolved = await resolveImageAsDataUrl(opts.goodImgUrl)
    const comma = resolved.indexOf(',')
    const base64 = comma >= 0 ? resolved.slice(comma + 1) : resolved
    if (!base64) throw new Error('goodImgUrl 解析为空')

    // 3) ★与 printPhotoWithHiTi 的关键差异★：
    //    不预先调 startService / getPrinterStatus —— sample 原版的 ServiceStart
    //    是 b_startService 单独操作的。如果 caller 没起 service，让 native 端
    //    直接抛 errCode，我们再把这个 errCode 装进 Error 抛给前端。
    console.log('[print/sampleHiTi] skipping pre-check startService/getPrinterStatus (sample behavior)')

    // 4) 真正打印 —— 这一步会在 native 端 raw new Thread 上跑：
    //    pre-set m_strTablesRoot → doService (sync) → post-clear m_strTablesRoot。
    //    sample 路径走 raw Thread，native 端用 AtomicBoolean+mainHandler watchdog 兜底（25s）。
    //    这里再加一层 TS Promise.race 双保险（30s），防止 native watchdog 漏触发。
    console.log('[print/sampleHiTi] calling HiTiPrinter.printPhotoSample...', {
      paperType,
      printCount,
      matte,
      printMode,
      base64Len: base64.length,
    })
    const printRes = await Promise.race([
      HiTiPrinter.printPhotoSample({
        base64,
        paperType,
        printCount,
        matte,
        printMode,
      }),
      new Promise<never>((_, reject) =>
        setTimeout(
          () => reject(new Error('[TS-watchdog] HiTi sample print did not resolve within 30s (likely SDK stuck)')),
          30_000,
        ),
      ),
    ])
    console.log('[print/sampleHiTi] printPhotoSample result:', printRes)

    // 5) native 侧 retrieveSampleData 输出被原样塞进 data / error：
    //    - errCode == 0 时 data 是 "<action> -ID<id> : err <0x0 ...>"
    //    - errCode != 0 时 error 是同款字符串（喂 Error.message 给前端）
    if (!printRes.ok) {
      const detail = printRes.error ?? 'unknown'
      try {
        await HiTiPrinter.captureLog({
          tag: 'SAMPLE_JS',
          msg: `printPhotoWithSampleHiTi FAILED, errCode=${detail}`,
        })
      } catch {}
      throw new Error(`[sample/rawThread] HiTi sample 打印失败：${detail}`)
    }
    try {
      await HiTiPrinter.captureLog({
        tag: 'SAMPLE_JS',
        msg: `printPhotoWithSampleHiTi SUCCESS, nativeOutput=${printRes.data ?? '(no data)'}`,
      })
    } catch {}
    console.log('[print/sampleHiTi] ====== SampleAPK-style HiTi print 完成 ======')
  } finally {
    try {
      await HiTiPrinter.closeLogSession()
      console.log('[print/sampleHiTi] native log session closed:', nativeLogPath)
    } catch (e) {
      console.warn('[print/sampleHiTi] closeLogSession failed:', e)
    }
  }
}

/**
 * 智能分发入口：默认走 HiTi（专用 USB 照片打印机），失败/不可用时自动降级到
 * 系统 PrintManager（@capgo/capacitor-printer）。
 *
 * 用户也可通过 {@link PrintEngine} 显式选择引擎，绕过自动降级。
 */
export type PrintEngine = 'auto' | 'hiti' | 'system'

export async function printPhoto(opts: PrintOptions & { engine?: PrintEngine; paperType?: number }): Promise<void> {
  const engine: PrintEngine = opts.engine ?? 'auto'
  console.log('[print] entry: printPhoto', {
    engine,
    goodImgUrlLen: opts.goodImgUrl.length,
    hasQrcodeUrl: !!opts.qrcodeUrl?.trim(),
    jobName: opts.jobName,
    paperType: opts.paperType,
  })

  if (engine === 'system') {
    console.log('[print] using system PrintManager')
    await printPhotoWithQrcode(opts)
    return
  }

  if (engine === 'hiti') {
    console.log('[print] using HiTi (forced)')
    await printPhotoWithHiTi(opts)
    return
  }

  // auto：先 HiTi，失败回退系统打印
  console.log('[print] auto mode: try HiTi first...')
  try {
    // ★双保险兜底：除了 native 端 PRINT_PHOTO_TIMEOUT_SECONDS 的 future.get(timeout)，
    // 这里再加一层 TS Promise.race，避免 native 端兜底编译失败 / 漏触发时 UI 永久卡在"打印中"。
    // 25s 比 native 的 20s 多 5s，确保正常情况下是 native 先回报错或成功。
    await Promise.race([
      printPhotoWithHiTi(opts),
      new Promise<never>((_, reject) =>
        setTimeout(
          () => reject(new Error('[TS-watchdog] HiTi print did not resolve within 25s (likely SDK stuck)')),
          25_000,
        ),
      ),
    ])
    console.log('[print] HiTi success, done')
  } catch (hitiErr) {
    const msg = hitiErr instanceof Error ? hitiErr.message : String(hitiErr)
    const stack = hitiErr instanceof Error ? hitiErr.stack : undefined
    console.warn('[print] HiTi 失败，按当前 DEBUG 策略直接抛错（不降级到系统打印）：', msg)
    console.warn('[print] HiTi stack:', stack)
    throw new Error(`[DEBUG] HiTi failed: ${msg}`)  // DEBUG: 临时暴露真实错误
  }
}
