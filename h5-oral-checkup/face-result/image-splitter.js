/**
 * 图片切割
 *
 * 把 fullImgUrl（左右拼接的整图）等分为左右两半：
 *   左半 [0, w/2)        = 矫正前(bad)  → badImgUrl
 *   右半 [w/2, w)        = 矫正后(good) → goodImgUrl
 */

const JPEG_QUALITY = 0.92

export function splitFullImage(url) {
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
        const make = (sx, sw) => {
          const canvas = document.createElement('canvas')
          canvas.width = sw
          canvas.height = h
          const ctx = canvas.getContext('2d')
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