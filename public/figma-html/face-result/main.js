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

/* ---------- 长按保存 ---------- */
function handleSave() {
  const msg = document.createElement('div')
  msg.textContent = '请长按照片保存'
  msg.style.cssText =
    'position:fixed;left:50%;top:50%;transform:translate(-50%,-50%);background:rgba(0,0,0,0.8);color:#fff;padding:3vw 6vw;border-radius:2vw;font-size:3.5vw;z-index:9999;'
  document.body.appendChild(msg)
  setTimeout(() => msg.remove(), 1800)
}

window.handleSave = handleSave

/* ---------- 启动 ---------- */
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', renderResult)
} else {
  renderResult()
}
