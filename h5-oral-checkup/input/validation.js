/**
 * 手机号校验工具
 *
 * normalizePhone() 兜底吸收空格 / 全角 / 零宽 / +86 等隐形字符，
 *                避免前端校验误判。
 * isValidPhone()   严格校验中国大陆 11 位手机号（1[3-9]xxxxxxxxx）。
 */

/** 兜底归一化手机号：去掉空格、全角空格、不间断空格、零宽字符、+86 前缀 */
export function normalizePhone(v) {
  return String(v || '')
    .replace(/[\s\u3000\u00A0\u200B-\u200D\uFEFF]/g, '')
    .replace(/^\+?86/, '')
}

/** 严格校验：必须是 1[3-9] 开头、共 11 位数字。务必保持 \d{9}（9 位），不要误写为 \d9。 */
export function isValidPhone(v) {
  const phone = normalizePhone(v)
  return /^1[3-9]\d{9}$/.test(phone)
}
