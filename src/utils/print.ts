import { Printer } from '@capgo/capacitor-printer'
import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'

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
