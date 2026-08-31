/**
 * 手机号校验工具
 *
 * <p>来源：{@code h5-oral-checkup/src/lib/phoneValidation.ts}
 * （口腔-面容变化 H5 项目），主项目移植版。
 *
 * <p>{@code normalizePhone()} 兜底吸收空格 / 全角 / 零宽 / +86 等隐形字符，
 * 避免前端校验误判。
 * <p>{@code isValidPhone()} 严格校验中国大陆 11 位手机号（1[3-9]xxxxxxxxx）。
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