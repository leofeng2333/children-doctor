/**
 * 图片切割
 *
 * 把 fullImgUrl（左右拼接的整图）等分为左右两半：
 *   左半 [0, w/2)        = 矫正前(bad)  → badImgUrl
 *   右半 [w/2, w)        = 矫正后(good) → goodImgUrl
 *
 * 与 Vue `useImageSplit` 的 splitRatio=0.5 行为一致。
 */

const JPEG_QUALITY = 0.92

/**
 * @param {string} url 整图 URL（可跨域，需服务端允许 CORS）。url 为空 / 加载失败时直接 reject。
 * @returns {Promise<{ badImgUrl: string, goodImgUrl: string }>}
 */
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
        // 左右两半在中线处多覆盖 10px，避免切线处出现 1px 拼接缝
        const OVERLAP_PX = 10
        const leftEnd = Math.min(w, half + OVERLAP_PX)        // 左半多往右吃 10px
        const rightStart = Math.max(0, half - OVERLAP_PX)     // 右半多往左吃 10px
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