/**
 * first 模式入口：姓名 + 手机号
 *
 * 启动流程：
 *   1. 输入姓名 + 手机号后点击 "查看照片"
 *   2. 不调任何接口——TODO: 后端验证接口待补，直接跳转 face-result.html
 *
 * 本文件使用 ES Modules，需在浏览器中通过 `<script type="module">` 引入。
 */

import { normalizePhone, isValidPhone } from '../input/validation.js'
import { showToast } from '../input/toast.js'

function getEls() {
  return {
    nameInput: document.getElementById('name'),
    phoneInput: document.getElementById('phone'),
    form: document.getElementById('form'),
    submitBtn: document.querySelector('.view-photos'),
  }
}

function handleSubmit(e) {
  e.preventDefault()
  const { nameInput, phoneInput, submitBtn } = getEls()
  const name = nameInput?.value.trim() || ''
  const phone = normalizePhone(phoneInput.value.trim())

  if (!name) {
    nameInput?.focus()
    showToast('请输入姓名', 'error')
    return false
  }
  if (!isValidPhone(phone)) {
    phoneInput?.focus()
    showToast('请输入正确的手机号', 'error')
    return false
  }

  // TODO: 后端验证接口接入后再写 verifyData
  console.log('[first] submit', { name, phone })

  if (submitBtn) {
    submitBtn.style.pointerEvents = 'none'
    submitBtn.textContent = '查 看 中 …'
  }

  setTimeout(() => {
    location.href = 'face-result.html'
  }, 300)
  return false
}

function boot() {
  const { form } = getEls()
  form?.addEventListener('submit', handleSubmit)
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot)
} else {
  boot()
}
