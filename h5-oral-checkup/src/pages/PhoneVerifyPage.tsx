import { useNavigate } from 'react-router-dom'
import { useRef, useState } from 'react'
import '@/styles/phone-verify.css'
import Footer from '@/components/Footer'
import { isValidPhone, normalizePhone } from '@/lib/phoneValidation'
import { requestSmsApi, SMS_API, SMS_VERIFY_API, SMS_SUCCESS_CODES } from '@/lib/smsApi'
import { useCountdown } from '@/lib/useCountdown'
import { showToast } from '@/components/useToast'
import { STORAGE_KEYS } from '@/lib/dataSource'

/**
 * default 模式：手机号 + 验证码 输入页
 *
 * 流程：
 *   1. 输入手机号 → 点获取验证码 → 调 SMS_API → 启动 60s 倒计时
 *   2. 输入验证码 → 调 SMS_VERIFY_API → 校验成功写 sessionStorage → 跳 /face-result
 */
export default function PhoneVerifyPage() {
  const navigate = useNavigate()
  const cd = useCountdown()
  const [phone, setPhone] = useState('')
  const [code, setCode] = useState('')
  const [sending, setSending] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const phoneInputRef = useRef<HTMLInputElement | null>(null)
  const codeInputRef = useRef<HTMLInputElement | null>(null)

  const getCodeLabel = sending
    ? '发送中…'
    : cd.isRunning
      ? `发送中，${cd.remaining}s后可重试`
      : '获取验证码'

  async function handleGetCode() {
    if (cd.isRunning || sending) return
    const p = normalizePhone(phone.trim())
    if (!isValidPhone(p)) {
      phoneInputRef.current?.focus()
      // 旧版用了 box-shadow 闪红，这里用 className 切换 1.2s 后还原
      const el = phoneInputRef.current
      if (el) {
        el.style.boxShadow = 'inset 0 0 0 0.3vw #ff4d4f'
        window.setTimeout(() => (el.style.boxShadow = ''), 1200)
      }
      showToast('请输入正确的手机号', 'error')
      return
    }

    setSending(true)
    try {
      const { ok, status, data } = await requestSmsApi(SMS_API, { phone: p })

      if (!ok) {
        cd.cancel()
        showToast(`请求失败 (HTTP ${status})`, 'error')
        return
      }

      if (data && typeof (data as { code?: unknown }).code !== 'undefined') {
        const code = (data as { code: unknown }).code
        if (typeof code === 'number' && !SMS_SUCCESS_CODES.has(code)) {
          cd.start(60)
          showToast((data as { message?: string }).message || '发送失败，请稍后重试', 'error')
          return
        }
      }

      cd.start(60)
      showToast((data as { message?: string })?.message || '验证码已发送', 'success')
    } catch (err) {
      cd.cancel()
      console.error('[SMS] error', err)
      const msg = (err instanceof Error && err.message) || '网络错误'
      showToast(`发送失败: ${msg}\n请检查网络或 CORS 配置`, 'error')
    } finally {
      setSending(false)
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const p = normalizePhone(phone.trim())
    const c = code.trim()
    if (!isValidPhone(p)) {
      phoneInputRef.current?.focus()
      showToast('请输入正确的手机号', 'error')
      return
    }
    if (!c) {
      codeInputRef.current?.focus()
      showToast('请输入验证码', 'error')
      return
    }

    setSubmitting(true)
    try {
      const { ok, status, data } = await requestSmsApi(SMS_VERIFY_API, { phone: p, code: c })

      if (!ok) {
        showToast(`请求失败 (HTTP ${status})`, 'error')
        return
      }

      if (data && typeof (data as { code?: unknown }).code !== 'undefined') {
        const bc = (data as { code: unknown }).code
        if (typeof bc === 'number' && !SMS_SUCCESS_CODES.has(bc)) {
          showToast((data as { message?: string }).message || '请求失败', 'error')
          return
        }
      }

      const verified = (data as { data?: { verified?: boolean } } | null)?.data?.verified === true
      if (!verified) {
        showToast((data as { message?: string })?.message || '验证码错误', 'error')
        codeInputRef.current?.focus()
        codeInputRef.current?.select()
        return
      }

      showToast((data as { message?: string })?.message || '校验成功', 'success')

      try {
        sessionStorage.setItem(STORAGE_KEYS.verifyData, JSON.stringify(data))
      } catch (err) {
        console.warn('[SMS-VERIFY] sessionStorage 写入失败', err)
      }

      setPhone('')
      setCode('')
      window.setTimeout(() => {
        navigate('/face-result')
      }, 500)
    } catch (err) {
      console.error('[SMS-VERIFY] error', err)
      const msg = (err instanceof Error && err.message) || '网络错误'
      showToast(`校验失败: ${msg}\n请检查网络或 CORS 配置`, 'error')
    } finally {
      setSubmitting(false)
    }
  }

  const submitLabel = submitting ? '校 验 中 …' : '查 看 照 片'

  return (
    <div className="content">
      <div className="header-illu" aria-hidden="true">
        <span className="header-illu-icon" />
      </div>

      <h1 className="title">
        请输入您在拍摄时
        <br />
        预留的手机号
      </h1>

      <form className="form" onSubmit={handleSubmit}>
        <div className="field">
          <input
            ref={phoneInputRef}
            id="phone"
            type="tel"
            inputMode="numeric"
            maxLength={11}
            placeholder="输入手机号"
            autoComplete="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
          />
        </div>

        <div className="row-2">
          <button
            type="button"
            id="get-code"
            className={`get-btn${cd.isRunning || sending ? ' sending' : ''}`}
            onClick={handleGetCode}
            disabled={cd.isRunning || sending}
          >
            {getCodeLabel}
          </button>
          <div className="code-field">
            <input
              ref={codeInputRef}
              id="code"
              type="text"
              inputMode="numeric"
              maxLength={6}
              size={1}
              placeholder="输入验证码"
              autoComplete="one-time-code"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
          </div>
        </div>

        <div className="divider" />

        <button type="submit" className="view-photos" disabled={submitting}>
          {submitLabel}
        </button>
      </form>

      <Footer />
    </div>
  )
}
