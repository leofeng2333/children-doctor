import { useEffect, useRef, useState } from 'react'
import '@/styles/face-result.css'
import {
  resolveResult,
  readLlmResult,
  loadVerifyDataToWindow,
  type ResultShape,
} from '@/lib/dataSource'
import {
  getDiagnosisCopyFromLLMResult,
  type DiagnosisCopy,
  DiagnosisCode,
} from '@/lib/diagnosisCopy'
import { splitFullImage } from '@/lib/splitImage'
import { showToast } from '@/components/useToast'

/**
 * 结果页：渲染左右两张对比卡 + 诊断描述。
 *
 * 数据源优先级（沿用原逻辑）：
 *   1. URL ?data=<base64-json>
 *   2. sessionStorage.faceResult:analysisResult
 *   3. sessionStorage.faceResult:verifyData
 *   4. DEFAULT_MOCK
 *
 * 图片处理：
 *   - 拿到 data 后若 bad/good 图缺失、但 fullImgUrl 存在 → 走 splitFullImage 切割
 *   - 切割走异步，UI 先用占位背景
 */
export default function FaceResultPage() {
  const [data, setData] = useState<ResultShape | null>(null)
  const [diagnosisCopy, setDiagnosisCopy] = useState<DiagnosisCopy | null>(null)
  const goodImgRef = useRef<HTMLImageElement | null>(null)

  useEffect(() => {
    loadVerifyDataToWindow()
    const resolved = resolveResult()
    const copy = getDiagnosisCopyFromLLMResult(readLlmResult())
    setData(resolved)
    setDiagnosisCopy(copy)

    // 暴露到 window，便于联调 / Cypress
    ;(window as unknown as { __faceResult?: unknown }).__faceResult = {
      data: resolved,
      diagnosisCopy: copy,
      diagnosisCode: resolved.categoryCode ?? DiagnosisCode.NORMAL,
    }
    console.log(
      '[face-result] 渲染完成，categoryCode =',
      resolved.categoryCode,
      '，title =',
      copy.title,
    )
  }, [])

  // 图片二次注入：data 准备好后再尝试 fullImgUrl → 切割
  useEffect(() => {
    if (!data) return
    if (data.goodImgUrl && data.badImgUrl) return
    if (!data.fullImgUrl) return
    let cancelled = false
    splitFullImage(data.fullImgUrl)
      .then((split) => {
        if (cancelled) return
        setData((prev) =>
          prev
            ? {
                ...prev,
                badImgUrl: prev.badImgUrl || split.badImgUrl,
                goodImgUrl: prev.goodImgUrl || split.goodImgUrl,
              }
            : prev,
        )
        console.log('[face-result] 已根据 fullImgUrl 切割生成左右两半')
      })
      .catch((err) => {
        console.warn('[face-result] fullImgUrl 切割失败，card-img 将保持灰色占位', err)
      })
    return () => {
      cancelled = true
    }
  }, [data])

  function handleSave() {
    const img = goodImgRef.current
    const src = img?.src
    if (!src) {
      showToast('图片还没准备好，稍后再试')
      return
    }
    const triggerDownload = (blob: Blob, filename: string) => {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = filename
      document.body.appendChild(a)
      a.click()
      a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
      showToast('已保存到手机')
    }
    if (src.startsWith('data:')) {
      const mimeMatch = /^data:([^;,]+)/.exec(src)
      const mime = mimeMatch?.[1] || 'image/jpeg'
      const ext = mime.split('/')[1] || 'jpg'
      const b64 = src.split(',')[1] || ''
      const bin = atob(b64)
      const len = bin.length
      const bytes = new Uint8Array(len)
      for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i)
      triggerDownload(new Blob([bytes], { type: mime }), `good.${ext}`)
      return
    }
    fetch(src, { mode: 'cors' })
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.blob()
      })
      .then((blob) => {
        const ext = (blob.type.split('/')[1] || 'jpg').replace('jpeg', 'jpg')
        triggerDownload(blob, `good.${ext}`)
      })
      .catch(() => showToast('保存失败，请重试'))
  }

  if (!data || !diagnosisCopy) return null

  const isNormal = data.categoryCode === DiagnosisCode.NORMAL
  const badImgSrc = data.badImgUrl || ''
  // NORMAL 时 split 出来的左右两半来自同一张原图，视觉一致 → UI 任取一张（card1 的 good 图）即可
  const goodImgSrc = data.goodImgUrl || ''

  return (
    <div className="content page-face-result">
      {!isNormal && (
        <h2 className="title-warning">
          啊哦，
          <br />
          颌面发育似乎不太妙！
        </h2>
      )}

      {!isNormal && (
        <div className="card card2">
          <div className="card-base">
            <div className="card-img-wrap">
              <img
                className="card-img"
                id="bad-img"
                alt=""
                src={badImgSrc || undefined}
                style={badImgSrc ? undefined : { visibility: 'hidden' }}
              />
            </div>
          </div>
          <div className="sticker" />
        </div>
      )}

      {isNormal && diagnosisCopy.opening ? (
        <h1 className="title-after title-after--normal">{diagnosisCopy.opening}</h1>
      ) : (
        <h1 className="title-after" id="title-after">
          但是不用担心，
          <br />
          矫正后面容会变成这样！
        </h1>
      )}

      <div className="card card1">
        <div className="card-base">
          <div className="card-img-wrap">
            <img
              className="card-img"
              id="good-img"
              alt=""
              ref={goodImgRef}
              src={goodImgSrc || undefined}
              style={goodImgSrc ? undefined : { visibility: 'hidden' }}
            />
          </div>
        </div>
        <div className="sticker" />
      </div>

      <div className="description">
        <div className="description-icon" />
        <div id="description-body">
          <span id="desc-title">{diagnosisCopy.title}</span>
          <span id="desc-opening">{diagnosisCopy.opening || ''}</span>
          <div id="desc-body">
            {(diagnosisCopy.body || []).map((p, i) => (
              <p key={i}>{p}</p>
            ))}
          </div>
          {diagnosisCopy.careTips && (
            <p id="desc-care">{'日常护理小贴士：' + diagnosisCopy.careTips}</p>
          )}
          {diagnosisCopy.habitNote && <p id="desc-habit">{diagnosisCopy.habitNote}</p>}
        </div>
      </div>

      <button className="save-btn" id="save-btn" onClick={handleSave}>
        保 存 到 手 机
      </button>
    </div>
  )
}
