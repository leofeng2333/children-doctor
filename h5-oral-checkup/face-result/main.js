/**
 * 入口
 *
 * 启动流程：
 *   1. 挂载 input.html 写入的 verifyData 到 window.__verifyData
 *   2. 解析数据源（URL > analysisResult > verifyData > mock）
 *   3. 解析诊断文案（从 llmResult.categoryCode 走 DIAGNOSIS_COPY_MAP）
 *   4. 注入 DOM
 *   5. 暴露到 window.__faceResult 便于联调 / Cypress
 *
 * 本文件使用 ES Modules，需在浏览器中通过 `<script type="module">` 引入。
 */

import { resolveResult, readLlmResult, loadVerifyDataToWindow } from './data-source.js'
import { DiagnosisCode, getDiagnosisCopyFromLLMResult } from './diagnosis-copy.js'
import { injectAll } from './injector.js'

async function renderResult() {
  // 1) 挂载 verifyData（保持原 IIFE 行为）
  loadVerifyDataToWindow()

  // 2) 解析数据
  const data = resolveResult()
  const diagnosisCopy = getDiagnosisCopyFromLLMResult(readLlmResult())

  // 3) 注入 DOM
  await injectAll(data, diagnosisCopy)

  // 4) 暴露到 window，便于联调 / Cypress
  window.__faceResult = {
    data,
    diagnosisCopy,
    diagnosisCode: data.categoryCode ?? DiagnosisCode.NORMAL,
  }
  console.log(
    '[face-result] 渲染完成，categoryCode =',
    window.__faceResult.diagnosisCode,
    '，title =',
    diagnosisCopy.title,
  )
}

/* ---------- 保存 goodImg 到手机 ----------
 * 触发方式：直接点击 #save-btn（不再依赖长按）。
 * 优先级：
 *   1. 读取 #good-img 的 src
 *      - 已是 dataURL（splitFullImage 切割结果）→ 直接走 a[download]
 *      - 是普通 URL（外部预切图） → fetch → blob → a[download]
 *   2. 没有可用 src → 弹 toast 提示
 */
function handleSave() {
  const img = document.getElementById('good-img')
  const src = img?.src
  if (!src) {
    return showToast('图片还没准备好，稍后再试')
  }

  const triggerDownload = (blob, filename) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    showToast('已保存到手机')
  }

  // dataURL（splitFullImage 的产物都是 data:image/jpeg;base64,...）
  if (src.startsWith('data:')) {
    const mimeMatch = /^data:([^;,]+)/.exec(src)
    const mime = mimeMatch?.[1] || 'image/jpeg'
    const ext = mime.split('/')[1] || 'jpg'
    // atob → Uint8Array → Blob
    const b64 = src.split(',')[1] || ''
    const bin = atob(b64)
    const len = bin.length
    const bytes = new Uint8Array(len)
    for (let i = 0; i < len; i++) bytes[i] = bin.charCodeAt(i)
    triggerDownload(new Blob([bytes], { type: mime }), `good.${ext}`)
    return
  }

  // 普通 URL
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

function showToast(text) {
  const msg = document.createElement('div')
  msg.textContent = text
  msg.style.cssText =
    'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);background:rgba(0,0,0,0.8);color:#fff;padding:3vw 6vw;border-radius:2vw;font-size:3.5vw;z-index:9999;'
  document.body.appendChild(msg)
  setTimeout(() => msg.remove(), 1800)
}

window.handleSave = handleSave

/* ---------- 启动 ---------- */
function boot() {
  // 等 injectAll 完成再绑按钮，保证 #good-img 已有 src
  renderResult().then(() => {
    const btn = document.getElementById('save-btn')
    btn?.addEventListener('click', handleSave)
  })
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot)
} else {
  boot()
}