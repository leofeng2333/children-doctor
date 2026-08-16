/**
 * 手机号校验工具
 *
 * normalizePhone() 兜底吸收空格 / 全角 / 零宽 / +86 等隐形字符，
 *                避免前端校验误判。
 * isValidPhone()   严格校验中国大陆 11 位手机号（1[3-9]xxxxxxxxx）。
 */

export function normalizePhone(v: string): string {
  return String(v ?? '')
    .replace(/[\s\u3000\u00A0\u200B-\u200D\uFEFF]/g, '')
    .replace(/^\+?86/, '')
}

export function isValidPhone(v: string): boolean {
  const phone = normalizePhone(v)
  return /^1[3-9]\d{9}$/.test(phone)
}
