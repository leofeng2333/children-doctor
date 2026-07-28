/**
 * 通用 Toast 提示
 *
 * 使用 base.css 里的 .toast / .toast.show / .toast.error / .toast.success 样式。
 * 带 showToast._t 单例定时器：连续调用会重置计时器。
 *
 * @param {string} msg
 * @param {'success' | 'error'} [type]
 */

export function showToast(msg, type) {
  let el = document.querySelector('.toast')
  if (!el) {
    el = document.createElement('div')
    el.className = 'toast'
    document.body.appendChild(el)
  }
  el.textContent = msg
  el.classList.remove('error', 'success', 'show')
  if (type) el.classList.add(type)
  // 强制 reflow，让 transition 重新触发
  void el.offsetWidth
  el.classList.add('show')
  clearTimeout(showToast._t)
  showToast._t = setTimeout(() => el.classList.remove('show'), 2200)
}
