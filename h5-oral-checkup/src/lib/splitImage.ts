/**
 * 图片切割
 *
 * 把 fullImgUrl 等分为左右两半（始终走 canvas 真实切割）：
 *   左半 [rightStart, w)  = badImgUrl
 *   右半 [0, leftEnd)     = goodImgUrl
 *
 * 异常面容（矫正前 vs 矫正后）：左右两半是不一样的两张图，用于对照展示。
 * 正常面容（categoryCode=0）：虽然原图本身已是完整面容，并没有"左右拼接"的概念，
 *                            但仍按统一的 split 流程走 canvas 切割，
 *                            切出来的 left/right 视觉上一致（同一张原图的两半），
 *                            UI 端任取一张渲染即可。
 */

const JPEG_QUALITY = 0.92

export interface SplitResult {
  badImgUrl: string
  goodImgUrl: string
}

export function splitFullImage(url: string): Promise<SplitResult> {
  return new Promise((resolve, reject) => {
    if (!url) {
      reject(new Error('[splitFullImage] url 为空'))
      return
    }
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      try {
        const w = img.naturalWidth
        const h = img.naturalHeight
        const half = Math.max(1, Math.floor(w / 2))
        const OVERLAP_PX = 10
        const leftEnd = Math.min(w, half + OVERLAP_PX)
        const rightStart = Math.max(0, half - OVERLAP_PX)
        const make = (sx: number, sw: number): string => {
          const canvas = document.createElement('canvas')
          canvas.width = sw
          canvas.height = h
          const ctx = canvas.getContext('2d')
          if (!ctx) throw new Error('[splitFullImage] canvas 2d ctx unavailable')
          ctx.drawImage(img, sx, 0, sw, h, 0, 0, sw, h)
          return canvas.toDataURL('image/jpeg', JPEG_QUALITY)
        }
        resolve({
          badImgUrl: make(rightStart, w - rightStart),
          goodImgUrl: make(0, leftEnd),
        })
      } catch (err) {
        reject(err instanceof Error ? err : new Error(String(err)))
      }
    }
    img.onerror = () => reject(new Error(`[splitFullImage] 主图加载失败: ${url}`))
    img.src = url
  })
}
