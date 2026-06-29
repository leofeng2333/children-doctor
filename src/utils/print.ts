import { Printer } from '@capgo/capacitor-printer'
import { Capacitor } from '@capacitor/core'

export interface PrintOptions {
  /** 主图 URL（good-img / 静态 success 图） */
  goodImgUrl: string
  /** 公众号二维码 URL（http(s) 或 file://） */
  qrcodeUrl: string
  /** 打印任务名（显示在系统打印队列） */
  jobName?: string
}

/**
 * 6 寸照片纸规格：4 × 6 in (102 × 152 mm)
 * 1 in = 96 CSS px
 * 主体：上方 goodImg（占 ~80% 高度），底部二维码 + 引导文案（占 ~20%）
 */
function buildPhotoHtml(opts: PrintOptions): string {
  const jobName = opts.jobName ?? '宝贝照片'
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
    .photo-footer img.qrcode {
      width: 0.85in;
      height: 0.85in;
      object-fit: contain;
      flex-shrink: 0;
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
      <img class="qrcode" src="${escapeAttr(opts.qrcodeUrl)}" alt="qrcode" />
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
  if (!opts.qrcodeUrl) {
    throw new Error('qrcodeUrl is required')
  }

  const html = buildPhotoHtml(opts)
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
