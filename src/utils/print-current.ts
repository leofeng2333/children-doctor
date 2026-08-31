import { Capacitor } from '@capacitor/core'
import { DualCamera } from '@/plugins/dual-camera'
import { HiTiPrinterCurrent } from '@/plugins/hiti-printer-current'

/**
 * path-current 页面级 HiTi USB 占用 hook。
 * 进入打印页面时调 initForPage（bind + claim USB），离开时 releaseForPage。
 * 避开与 UVC camera 抢占同一 USB bus。
 */
export async function printPageMounted(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  console.log('[print/current] print page mounted, init HiTi')
  try {
    const r = await HiTiPrinterCurrent.initForPage()
    console.log('[print/current] initForPage result:', r)
  } catch (e) {
    console.warn('[print/current] initForPage threw:', e)
  }
}

export async function printPageUnmounted(): Promise<void> {
  if (Capacitor.getPlatform() !== 'android') return
  console.log('[print/current] print page unmounted, release HiTi')
  try {
    const r = await HiTiPrinterCurrent.releaseForPage()
    console.log('[print/current] releaseForPage result:', r)
  } catch (e) {
    console.warn('[print/current] releaseForPage threw:', e)
  }
}

export interface PrintOptions {
  goodImgUrl: string
  jobName?: string
}

/**
 * path-current：单路径打印入口。
 *
 * <p>策略：原图 base64 → Java 解码 → letterbox fit-within + SAFE_MARGIN_PX（任意 paperType）→
 * PrintPara → sync doService + 20s timeout + retry 1 次。
 *
 * <p>关键设计：
 * <ul>
 *   <li>TS 端不做任何尺寸适配（不缩放 / 不裁切 / 不 letterbox / 不限制最大像素），原图直接送 native</li>
 *   <li>所有图片尺寸适配集中在 Java {@link HiTiPrinterManager_Current#letterboxBitmapToPaper}：
 *       等比缩放到 PaperSize 物理像素的安全区内（四周扣 SAFE_MARGIN_PX=24px ≈ 1.9mm），
 *       居中放在白色画布上 → 按图片本身比例打印 + 至少留一点白边做缓冲处理</li>
 *   <li>Java 端 warmupGate：等 ClockTask 跑 2 个周期 = 6s（仅冷启动第一次 print）</li>
 *   <li>Java 端 retry：doService timeout 后 2s 重试 1 次（区分冷启动 vs SDK 真挂）</li>
 * </ul>
 */
const PAPER_JPEG_QUALITY = 0.92

async function loadImage(input: string): Promise<HTMLImageElement> {
  const dataUrl = await resolveImageAsDataUrl(input)
  if (!dataUrl) throw new Error('loadImage: empty image source')
  const img = new Image()
  img.decoding = 'async'
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve()
    img.onerror = () => reject(new Error('image failed to decode'))
    img.src = dataUrl
  })
  try {
    await img.decode()
  } catch (e) {
    console.warn('[print/current] img.decode() rejected, continuing:', e)
  }
  return img
}

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
      return await new Promise((resolve, reject) => {
        reader.onload = () => resolve(reader.result as string)
        reader.onerror = () => reject(reader.error ?? new Error('FileReader failed'))
        reader.readAsDataURL(blob)
      })
    }
    throw new Error(`resolveImageAsDataUrl: unsupported input "${input}"`)
  }
  console.log('[print/current] native path calling DualCamera.readImageAsBase64, input=' + input)
  const { base64 } = await DualCamera.readImageAsBase64({ input })
  return `data:image/jpeg;base64,${base64}`
}

/**
 * path-current: image preparation。
 *
 * <p>所有图片尺寸适配都在原生端（{@link HiTiPrinterManager_Current#letterboxBitmapToPaper}）：
 * 任意输入 bitmap → 等比缩放到 PaperSize 物理像素的安全区内（四周扣 SAFE_MARGIN_PX=24px ≈ 1.9mm）→
 * 居中放在白色画布上 → 送 SDK。
 *
 * <p>本函数 TS 端不做任何尺寸缩放 / 裁切 / letterbox / 最大像素限制，原图 1:1 绘制到 canvas。
 * 唯一预处理：把任意 image format（PNG / WebP / HEIC 等）转成 JPEG dataURL，
 * 确保原生端 BitmapFactory.decodeFile 能稳定解码。
 *
 * <p>大小权衡：原图 base64 字符串可能较大（4K JPEG ≈ 1.4MB base64），IPC 传输成本 < 100ms，
 * BitmapFactory 解码 + letterbox 全部在原生端完成。如果未来出现大图 IPC 性能问题，
 * 应在原生端用 BitmapFactory.Options.inSampleSize / inJustDecodeBounds 控制解码内存，
 * 而不是回到 TS 端做尺寸适配（违反"所有尺寸适配在原生端"的契约）。
 */
export async function prepImageForNative(input: string): Promise<string> {
  if (!input) throw new Error('prepImageForNative: input is empty')
  if (input.startsWith('data:image/jpeg')) return input

  const img = await loadImage(input)
  const srcW = img.naturalWidth || img.width
  const srcH = img.naturalHeight || img.height
  if (!srcW || !srcH) throw new Error(`image has zero dimensions (${srcW}x${srcH})`)

  // 原图 1:1 绘制到 canvas，所有尺寸适配交给原生端 letterboxBitmapToPaper
  const canvas = document.createElement('canvas')
  canvas.width = srcW
  canvas.height = srcH
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('2d context unavailable')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, 0, 0, srcW, srcH)

  const dataUrl: string = await new Promise((resolve, reject) => {
    try {
      const out = canvas.toDataURL('image/jpeg', PAPER_JPEG_QUALITY)
      if (!out || out === 'data:,') reject(new Error('toDataURL returned empty'))
      else resolve(out)
    } catch (e) {
      reject(e instanceof Error ? e : new Error(String(e)))
    }
  })
  img.src = ''
  return dataUrl
}

export async function printPhoto(opts: PrintOptions & { paperType?: number }): Promise<void> {
  if (!opts.goodImgUrl) throw new Error('goodImgUrl is required')
  const paperType = opts.paperType ?? 2
  console.log('[print/current] ====== HiTi 打印开始 ======')
  console.log('[print/current] opts:', {
    goodImgUrlLen: opts.goodImgUrl.length,
    jobName: opts.jobName,
    paperType,
  })

  let nativeLogPath = ''
  try {
    const session = await HiTiPrinterCurrent.startLogSession()
    if (session.ok && session.data?.path) {
      nativeLogPath = session.data.path
      console.log('[print/current] native log session started:', nativeLogPath)
    }
  } catch (e) {
    console.warn('[print/current] startLogSession threw:', e)
  }

  try {
    await Promise.race([
      (async () => {
        const resolved = await prepImageForNative(opts.goodImgUrl)
        console.log('[print/current] prepImageForNative:', {
          isDataUrl: resolved.startsWith('data:'),
          length: resolved.length,
          prefix: resolved.slice(0, 40),
        })
        try {
          await HiTiPrinterCurrent.captureLog({
            tag: 'TS',
            msg: `printPhoto start, paperType=${paperType} nativeLog=${nativeLogPath}`,
          })
        } catch {}
        const comma = resolved.indexOf(',')
        const base64 = comma >= 0 ? resolved.slice(comma + 1) : resolved
        if (!base64) throw new Error('goodImgUrl 解析为空')

        console.log('[print/current] step 1/2: startService...')
        const startRes = await HiTiPrinterCurrent.startService()
        console.log('[print/current] startService result:', startRes)
        if (!startRes.ok) throw new Error(`HiTi service 启动失败：${startRes.error}`)

        console.log('[print/current] step 2/2: printPhoto...')
        const printRes = await HiTiPrinterCurrent.printPhoto({
          base64,
          paperType,
        })
        console.log('[print/current] printPhoto result:', printRes)
        if (!printRes.ok) throw new Error(`HiTi 打印失败：${printRes.error}`)
      })(),
      new Promise<never>((_, reject) =>
        setTimeout(
          () => reject(new Error('[TS-watchdog] HiTi print did not resolve within 35s (path-current: warmup 6s + retry 20s + 20s timeout + slack)')),
          35_000,
        ),
      ),
    ])
    console.log('[print/current] ====== HiTi 打印完成 ======')
  } catch (hitiErr) {
    const msg = hitiErr instanceof Error ? hitiErr.message : String(hitiErr)
    console.warn('[print/current] HiTi failed:', msg)
    throw new Error(`HiTi failed: ${msg}`)
  } finally {
    try {
      await HiTiPrinterCurrent.closeLogSession()
      console.log('[print/current] native log session closed:', nativeLogPath)
    } catch (e) {
      console.warn('[print/current] closeLogSession failed:', e)
    }
  }
}
