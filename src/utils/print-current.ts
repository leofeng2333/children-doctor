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
 * <p>策略：直接 base64 → Java 解码 → paperType=2 fast path 缩放到 1844×1240 →
 * PrintPara → sync doService + 20s timeout + retry 1 次。
 *
 * <p>关键设计：
 * <ul>
 *   <li>不再做 TS 端 cover-fit / letterbox，bitmap 直接送 native</li>
 *   <li>Java 端 paperType=2 fast path 缩放：等比放大 + center-crop 到 1844×1240</li>
 *   <li>Java 端 warmupGate：等 ClockTask 跑 2 个周期 = 6s（仅冷启动第一次 print）</li>
 *   <li>Java 端 retry：doService timeout 后 2s 重试 1 次（区分冷启动 vs SDK 真挂）</li>
 * </ul>
 */
const PAPER_W = 1844
const PAPER_H = 1240
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
 * path-current: 简化的 image preparation。
 *
 * <p>不做 cover-fit / letterbox（之前两个都有问题）。直接把原图作为 PNG/JPEG 解码，
 * 再编码为 JPEG 输出。Java 端 fast path 会缩放到 1844×1240，所以这里不需要做
 * 任何 cover-fit —— 等比放大由 Java 完成。
 *
 * <p>唯一做的预处理：转成 JPEG dataURL（确保 native BitmapFactory.decodeFile 能解码）。
 * 如果 input 已经是 JPEG dataURL，直接返回。
 */
export async function prepImageForNative(input: string): Promise<string> {
  if (!input) throw new Error('prepImageForNative: input is empty')
  if (input.startsWith('data:image/jpeg')) return input

  const img = await loadImage(input)
  const srcW = img.naturalWidth || img.width
  const srcH = img.naturalHeight || img.height
  if (!srcW || !srcH) throw new Error(`image has zero dimensions (${srcW}x${srcH})`)

  // 输出尺寸限制：避免 base64 过大传输（实测 raw 1844×1240 JPEG ≈ 320KB）。
  // 如果输入超过 2K 像素，先等比缩小。
  let targetW = srcW
  let targetH = srcH
  if (srcW > 2000 || srcH > 2000) {
    const ratio = Math.min(2000 / srcW, 2000 / srcH)
    targetW = Math.round(srcW * ratio)
    targetH = Math.round(srcH * ratio)
  }

  const canvas = document.createElement('canvas')
  canvas.width = targetW
  canvas.height = targetH
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('2d context unavailable')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(img, 0, 0, targetW, targetH)

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
