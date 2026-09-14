import { useNavigate } from 'react-router-dom'
import { useRef, useState } from 'react'
import '@/styles/phone-verify.css'
import { isValidPhone, normalizePhone } from '@/lib/phoneValidation'
import { requestSmsApi, SMS_API, SMS_VERIFY_API, SMS_SUCCESS_CODES } from '@/lib/smsApi'
import {
  isLlmTaskSuccess,
  LLM_TASK_BY_PHONE_API,
  requestLlmTaskGetApi,
} from '@/lib/llmTaskApi'
import { useCountdown } from '@/lib/useCountdown'
import { showToast } from '@/components/useToast'
import { STORAGE_KEYS } from '@/lib/dataSource'

/**
 * 校验节点是否含有效分析结果。
 * 判定标准与 dataSource.ts 的 pickAiResult / pickLlmResult 保持一致：
 * 必须是对象 + 含 .result 且 .result 也是对象。
 */
function hasAnalysisResult(node: unknown): boolean {
  if (!node || typeof node !== 'object') return false
  const r = (node as Record<string, unknown>).result
  return !!r && typeof r === 'object'
}

/**
 * default 模式：手机号 + 验证码 输入页
 *
 * 流程：
 *   1. 输入手机号 → 点获取验证码 → 调 SMS_API → 启动 60s 倒计时
 *   2. 输入验证码 → 调 SMS_VERIFY_API → 校验通过后
 *   3. 调 GET /api/ai/llm-task/by-phone?phone=xxx 反查完整分析包
 *   4. 解析响应 data.resultJson (string) → 解码后含 aiAnalysis + llmAnalysis.result
 *   5. 把解码对象写入 sessionStorage.verifyData → 跳 /face-result
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

      // 验证码通过后,按 phone 反查最新完整分析包。by-phone 响应里真正的
      // 分析数据在 data.resultJson 字符串字段里(后端把对象 stringify 后
      // 塞到该字段),需要先 JSON.parse 解码,再校验 ai/llm 是否都含 .result。
      const byPhoneResp = await requestLlmTaskGetApi(LLM_TASK_BY_PHONE_API, { phone: p })
      if (!isLlmTaskSuccess(byPhoneResp.status, byPhoneResp.data)) {
        showToast(
          (byPhoneResp.data as { message?: string } | null)?.message ||
            `数据查询失败 (HTTP ${byPhoneResp.status})`,
          'error',
        )
        return
      }

      const resultJsonStr = (
        byPhoneResp.data as { data?: { resultJson?: unknown } } | null
      )?.data?.resultJson
      if (typeof resultJsonStr !== 'string' || !resultJsonStr) {
        showToast('返回数据格式异常,请稍后重试', 'error')
        return
      }

      let parsed: unknown
      try {
        parsed = JSON.parse(resultJsonStr)
      } catch (err) {
        console.warn('[SMS-VERIFY] resultJson 解析失败', err)
        showToast('返回数据格式异常,请稍后重试', 'error')
        return
      }

      // 校验解码后的对象中必须同时含 aiAnalysis 和 llmAnalysis,
      // 二者缺一不允许跳转(规则与原 SMS verify 校验一致)
      const parsedObj = parsed as
        | { aiAnalysis?: unknown; llmAnalysis?: unknown }
        | null
      if (!hasAnalysisResult(parsedObj?.aiAnalysis) || !hasAnalysisResult(parsedObj?.llmAnalysis)) {
        showToast('分析数据缺失,请稍后再试', 'error')
        return
      }

      showToast((byPhoneResp.data as { message?: string })?.message || '校验成功', 'success')

      try {
        // 写入 verifyData 的是解码后的 verify-shape 对象本身(不是 by-phone
        // 的外层包装)。face-result 的 pickAiResult/pickLlmResult 会找到顶层
        // aiAnalysis/llmAnalysis 并取其 .result,与 SMS verify 自带数据等价。
        sessionStorage.setItem(STORAGE_KEYS.verifyData, JSON.stringify(parsed))
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
    <div className="content page-phone">
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
    </div>
  )
}
