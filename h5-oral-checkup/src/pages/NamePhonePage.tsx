import { useLocation, useNavigate } from 'react-router-dom'
import { useRef, useState } from 'react'
import '@/styles/name-phone.css'
import { isValidPhone, normalizePhone } from '@/lib/phoneValidation'
import { showToast } from '@/components/useToast'
import {
  LLM_TASK_BIND_PHONE_API,
  LLM_TASK_RESULT_API,
  isLlmTaskSuccess,
  requestLlmTaskGetApi,
  requestLlmTaskPostApi,
} from '@/lib/llmTaskApi'
import { STORAGE_KEYS } from '@/lib/dataSource'

/**
 * first 模式：姓名 + 手机号 输入页（首次扫码进入）
 *
 * 流程：
 *   1. 称呼语必填 + 手机号格式校验
 *   2. 从 URL query 读 llmAnalysisId（优先 id，兼容 llmAnalysisId）
 *   3. POST /api/ai/llm-task/bind-phone 绑定 llmAnalysisId + phone
 *   4. GET  /api/ai/llm-task?llmAnalysisId=xxx  拿结果
 *   5. 把结果以 verifyData 格式写入 sessionStorage，跳 /face-result
 *
 * 跳转逻辑与 PhoneVerifyPage 一致：toast 提示 + 500ms 后 navigate。
 */
export default function NamePhonePage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [name, setName] = useState('')
  const [phone, setPhone] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const nameInputRef = useRef<HTMLInputElement | null>(null)
  const phoneInputRef = useRef<HTMLInputElement | null>(null)

  function readLlmAnalysisId(): string {
    const params = new URLSearchParams(location.search)
    const id = (params.get('id') || params.get('llmAnalysisId') || '').trim()
    return id
  }

  async function handleSubmit(e: React.FormEvent) {
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

    const llmAnalysisId = readLlmAnalysisId()
    if (!llmAnalysisId) {
      showToast('报告链接无效，请重新扫码', 'error')
      return
    }

    setSubmitting(true)
    try {
      // 1) 绑定手机号
      const bindResp = await requestLlmTaskPostApi(LLM_TASK_BIND_PHONE_API, {
        llmAnalysisId,
        phone: p,
      })
      if (!isLlmTaskSuccess(bindResp.status, bindResp.data)) {
        showToast(
          (bindResp.data as { message?: string } | null)?.message ||
            `绑定失败 (HTTP ${bindResp.status})`,
          'error',
        )
        return
      }

      // 2) 拉取任务结果
      const resultResp = await requestLlmTaskGetApi(LLM_TASK_RESULT_API, { llmAnalysisId })
      if (!isLlmTaskSuccess(resultResp.status, resultResp.data)) {
        showToast(
          (resultResp.data as { message?: string } | null)?.message ||
            `获取报告失败 (HTTP ${resultResp.status})`,
          'error',
        )
        return
      }

      // 3) 写 sessionStorage（face-result 兼容 verifyData 格式）
      try {
        sessionStorage.setItem(
          STORAGE_KEYS.verifyData,
          JSON.stringify(resultResp.data),
        )
      } catch (err) {
        console.warn('[first] sessionStorage 写入失败', err)
      }

      showToast((resultResp.data as { message?: string } | null)?.message || '校验成功', 'success')

      // 4) 跳转（与 PhoneVerifyPage 一致：500ms 后 navigate）
      window.setTimeout(() => {
        navigate('/face-result')
      }, 500)
    } catch (err) {
      console.error('[first] submit error', err)
      const msg = (err instanceof Error && err.message) || '网络错误'
      showToast(`请求失败: ${msg}\n请检查网络或 CORS 配置`, 'error')
    } finally {
      setSubmitting(false)
    }
  }

  const submitLabel = submitting ? '查看中 …' : '查看报告'

  return (
    <div className="content page-name">
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
    </div>
  )
}
