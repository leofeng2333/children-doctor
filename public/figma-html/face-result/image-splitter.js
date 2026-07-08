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
 * @param {string} url 整图 URL（可跨域，需服务端允许 CORS）
 * @returns {Promise<{ badImgUrl: string, goodImgUrl: string }>}
 */
export function splitFullImage(url) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.onload = () => {
      try {
        const w = img.naturalWidth
        const h = img.naturalHeight
        const half = Math.max(1, Math.floor(w / 2))
        const make = (sx, sw) => {
          const canvas = document.createElement('canvas')
          canvas.width = sw
          canvas.height = h
          const ctx = canvas.getContext('2d')
          ctx.drawImage(img, sx, 0, sw, h, 0, 0, sw, h)
          return canvas.toDataURL('image/jpeg', JPEG_QUALITY)
        }
        resolve({
          badImgUrl: make(0, half),
          goodImgUrl: make(half, w - half),
        })
      } catch (err) {
        reject(err)
      }
    }
    img.onerror = (err) => reject(err)
    img.src = url
  })
}
