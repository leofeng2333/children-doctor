/**
 * 入口：手机号 + 验证码输入页
 *
 * 启动流程：
 *   1. DOMContentLoaded 后查 DOM，绑按钮事件
 *   2. 获取验证码 → 调 SMS_API → 成功后启动 60s 倒计时
 *   3. 提交 → 调 SMS_VERIFY_API → 校验成功后写 sessionStorage，跳转 face-result.html
 *
 * 本文件使用 ES Modules，需在浏览器中通过 `<script type="module">` 引入。
 */

import { requestSmsApi, SMS_API, SMS_VERIFY_API, SMS_SUCCESS_CODES } from './api.js'
import { normalizePhone, isValidPhone } from './validation.js'
import { createCountdown } from './countdown.js'
import { showToast } from './toast.js'

function getEls() {
  return {
    phoneInput: document.getElementById('phone'),
    codeInput: document.getElementById('code'),
    getCodeBtn: document.getElementById('get-code'),
    form: document.getElementById('form'),
    submitBtn: document.querySelector('.view-photos'),
  }
}

async function handleGetCode(countdownCtrl) {
  if (countdownCtrl.isRunning()) return
  const { phoneInput, getCodeBtn } = getEls()
  const phone = normalizePhone(phoneInput.value.trim())
  if (!isValidPhone(phone)) {
    phoneInput.focus()
    phoneInput.style.boxShadow = 'inset 0 0 0 0.3vw #ff4d4f'
    setTimeout(() => (phoneInput.style.boxShadow = ''), 1200)
    showToast('请输入正确的手机号', 'error')
    return
  }

  getCodeBtn.disabled = true
  getCodeBtn.classList.add('sending')
  getCodeBtn.textContent = '发送中…'

  try {
    const { ok, status, data } = await requestSmsApi(SMS_API, { phone })

    if (!ok) {
      countdownCtrl.cancel()
      showToast(`请求失败 (HTTP ${status})`, 'error')
      return
    }

    if (data && typeof data.code !== 'undefined' && !SMS_SUCCESS_CODES.has(data.code)) {
      countdownCtrl.start(60)
      showToast(data.message || '发送失败，请稍后重试', 'error')
      return
    }

    countdownCtrl.start(60)
    showToast(data?.message || '验证码已发送', 'success')
  } catch (err) {
    countdownCtrl.cancel()
    console.error('[SMS] error', err)
    const msg = (err && err.message) || '网络错误'
    showToast(`发送失败: ${msg}\n请检查网络或 CORS 配置`, 'error')
  }
}

async function handleSubmit(e) {
  e.preventDefault()
  const { phoneInput, codeInput, submitBtn } = getEls()
  const phone = normalizePhone(phoneInput.value.trim())
  const code = codeInput.value.trim()
  if (!isValidPhone(phone)) {
    phoneInput.focus()
    showToast('请输入正确的手机号', 'error')
    return false
  }
  if (!code) {
    codeInput.focus()
    showToast('请输入验证码', 'error')
    return false
  }

  const originalText = submitBtn ? submitBtn.textContent : ''
  if (submitBtn) {
    submitBtn.textContent = '校 验 中 …'
    submitBtn.style.pointerEvents = 'none'
  }

  try {
    const { ok, status, data } = await requestSmsApi(SMS_VERIFY_API, { phone, code })

    if (!ok) {
      showToast(`请求失败 (HTTP ${status})`, 'error')
      restoreSubmit(submitBtn, originalText)
      return false
    }

    const businessFailed =
      data && typeof data.code !== 'undefined' && !SMS_SUCCESS_CODES.has(data.code)
    if (businessFailed) {
      showToast(data.message || '请求失败', 'error')
      restoreSubmit(submitBtn, originalText)
      return false
    }

    const verified = data?.data?.verified === true
    if (!verified) {
      showToast(data?.message || '验证码错误', 'error')
      codeInput.focus()
      codeInput.select()
      restoreSubmit(submitBtn, originalText)
      return false
    }

    showToast(data?.message || '校验成功', 'success')
    restoreSubmit(submitBtn, originalText)

    // 把后端返回写进 sessionStorage，face-result.html 读取
    try {
      sessionStorage.setItem('faceResult:verifyData', JSON.stringify(data))
    } catch (err) {
      console.warn('[SMS-VERIFY] sessionStorage 写入失败', err)
    }

    // 跳转前清空输入框，避免视觉残留 / 回退看到旧值
    if (phoneInput) {
      phoneInput.value = ''
      phoneInput.blur()
    }
    if (codeInput) {
      codeInput.value = ''
      codeInput.blur()
    }
    setTimeout(() => {
      location.href = 'face-result.html'
    }, 500)
    return false
  } catch (err) {
    console.error('[SMS-VERIFY] error', err)
    const msg = (err && err.message) || '网络错误'
    showToast(`校验失败: ${msg}\n请检查网络或 CORS 配置`, 'error')
    restoreSubmit(submitBtn, originalText)
  }
  return false
}

function restoreSubmit(submitBtn, originalText) {
  if (!submitBtn) return
  submitBtn.textContent = originalText
  submitBtn.style.pointerEvents = ''
}

function boot() {
  const { phoneInput, codeInput, getCodeBtn, form } = getEls()
  const countdownCtrl = createCountdown(getCodeBtn)

  // inline-onclick 风格的事件 → 用 addEventListener 处理
  getCodeBtn?.addEventListener('click', () => handleGetCode(countdownCtrl))
  form?.addEventListener('submit', handleSubmit)

  // 调试便利：暴露到 window，方便联调
  window.__input = { handleGetCode, handleSubmit, getEls, countdownCtrl }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot)
} else {
  boot()
}
