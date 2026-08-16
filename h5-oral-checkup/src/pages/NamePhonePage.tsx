import { useNavigate } from 'react-router-dom'
import { useRef, useState } from 'react'
import '@/styles/name-phone.css'
import Footer from '@/components/Footer'
import { isValidPhone, normalizePhone } from '@/lib/phoneValidation'
import { showToast } from '@/components/useToast'

/**
 * first 模式：姓名 + 手机号 输入页（首次扫码进入）
 *
 * 流程：
 *   1. 称呼语必填 + 手机号格式校验
 *   2. TODO: 后端验证接口接入后再写 verifyData
 *   3. 校验通过 → 跳 /face-result
 */
export default function NamePhonePage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const nameInputRef = useRef<HTMLInputElement | null>(null)
  const phoneInputRef = useRef<HTMLInputElement | null>(null)

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const n = name.trim()
    const p = normalizePhone(phone.trim())
    if (!n) {
      nameInputRef.current?.focus()
      showToast('请输入姓名', 'error')
      return
    }
    if (!isValidPhone(p)) {
      phoneInputRef.current?.focus()
      showToast('请输入正确的手机号', 'error')
      return
    }

    // TODO: 后端验证接口接入后再写 verifyData
    console.log('[first] submit', { name: n, phone: p })

    setSubmitting(true)
    window.setTimeout(() => {
      navigate('/face-result')
    }, 300)
  }

  const submitLabel = submitting ? '查 看 中 …' : '查 看 报 告'

  return (
    <div className="content">
      <div className="header-illu" aria-hidden="true">
        <span className="header-illu-icon" />
      </div>

      <h1 className="title">
        填写手机号
        <br />
        可在公众号中永久查看报告
      </h1>

      <form className="form" onSubmit={handleSubmit}>
        <div className="field-group">
          <label className="field-label" htmlFor="name">
            我该怎么称呼你呢？
          </label>
          <div className="field">
            <input
              ref={nameInputRef}
              id="name"
              type="text"
              maxLength={40}
              placeholder="输入姓名"
              autoComplete="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
        </div>

        <div className="field-group">
          <label className="field-label" htmlFor="phone">
            你的联系方式？
          </label>
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
        </div>

        <button type="submit" className="view-photos" disabled={submitting}>
          {submitLabel}
        </button>

        <p className="tips">* 请记住所填写的手机号，方便下次查看电子诊断结果时使用哦！</p>
      </form>

      <Footer />
    </div>
  )
}
