import { Printer } from '@capgo/capacitor-printer'
import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'
import { HiTiPrinter } from '@/plugins/hiti-printer'

export interface PrintOptions {
  /** 主图 URL（good-img / 静态 success 图） */
  goodImgUrl: string
  /**
   * 公众号二维码 URL（http(s) 或 file://）。
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
  if (input.startsWith('data:')) return input

  const isWeb = Capacitor.getPlatform() === 'web'

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
      return dataUrl
    }
    // Bare local path on web: not supported.
    throw new Error(`resolveImageAsDataUrl: unsupported input "${input}"`)
  }

  // Native: route through the DualCamera plugin which has privileged
  // access to ContentResolver / app cache directories.
  const { base64 } = await DualCamera.readImageAsBase64({ input })
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
  // qrcodeUrl 可选：未传 / 空串时，模板只占位（不渲染图片）

  // 在 Android 上把 file:// / http(s):// 转成 data: URL，避免空 origin
  // WebView 拒绝加载本地资源导致打印主图黑屏。
  const [resolvedGoodImg, resolvedQrcode] = await Promise.all([
    resolveImageAsDataUrl(opts.goodImgUrl),
    opts.qrcodeUrl?.trim() ? resolveImageAsDataUrl(opts.qrcodeUrl.trim()) : Promise.resolve(''),
  ])

  const html = buildPhotoHtml({
    ...opts,
    goodImgUrl: resolvedGoodImg,
    qrcodeUrl: resolvedQrcode,
  })
  const jobName = opts.jobName ?? '宝贝照片'

  // Web 平台 / 浏览器降级
  if (Capacitor.getPlatform() === 'web') {
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
    return
  }

  await Printer.printHtml({
    name: jobName,
    html,
  })
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

  // 取 base64（dataURL → base64）。HiTi 路径是独立调用，复用
  // resolveImageAsDataUrl 的同源/跨域处理逻辑避免重复。
  const resolved = await resolveImageAsDataUrl(opts.goodImgUrl)
  const comma = resolved.indexOf(',')
  const base64 = comma >= 0 ? resolved.slice(comma + 1) : resolved
  if (!base64) throw new Error('goodImgUrl 解析为空')

  // 1) 起 service。HiTi SDK 要求所有 USB op 之前必须先 StartService,
  // 否则 USB_CHECK_PRINTER_STATUS 之类直接返回 "Service is not start"。
  const startRes = await HiTiPrinter.startService()
  if (!startRes.ok) throw new Error(`HiTi service 启动失败：${startRes.error}`)

  // 2) 探测打印机是否就绪
  const statusRes = await HiTiPrinter.getPrinterStatus()
  if (!statusRes.ok) {
    throw new Error(`HiTi 打印机不可用：${statusRes.error}`)
  }
  // SDK 返回 null 表示"无 status 数据"，但不一定是错；放过继续打。
  if (statusRes.data && statusRes.data.statusValue === 0x00000080) {
    throw new Error('HiTi 打印机未连接或未开机（status=0x00000080）')
  }

  // 3) 发打印任务（让 Java 侧自己把 base64 写盘，避免引入 Filesystem 插件）
  const printRes = await HiTiPrinter.printPhotoBase64({
    base64,
    paperType: opts.paperType ?? 2,
  })
  if (!printRes.ok) throw new Error(`HiTi 打印失败：${printRes.error}`)
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

  if (engine === 'system') {
    await printPhotoWithQrcode(opts)
    return
  }

  if (engine === 'hiti') {
    await printPhotoWithHiTi(opts)
    return
  }

  // auto：先 HiTi，失败回退系统打印
  try {
    await printPhotoWithHiTi(opts)
  } catch (hitiErr) {
    const msg = hitiErr instanceof Error ? hitiErr.message : String(hitiErr)
    console.warn('[print] HiTi 不可用，自动降级到系统打印：', msg)
    throw new Error(`[DEBUG] HiTi failed: ${msg}`)  // DEBUG: 临时暴露真实错误
  }
}
