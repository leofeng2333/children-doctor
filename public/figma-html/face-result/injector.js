/**
 * DOM 注入
 *
 * 负责把 ResultShape + DiagnosisCopy 渲染到页面上的固定锚点。
 * 锚点清单（与 face-result.html 严格对应）：
 *   #bad-img            - card2 坏图（矫正前）
 *   #good-img           - card1 好图（矫正后）
 *   #description-body   - 诊断文案容器
 *     └ #desc-title    - 章节标题
 *     └ #desc-opening  - 引导句
 *     └ #desc-body     - 正文多段
 *     └ #desc-care     - 日常护理小贴士
 *     └ #desc-habit    - 不良口腔习惯提示
 *
 * 注：title-warning 是与 Vue 端 DetailAnalysisView.vue 对齐的静态文案，
 *     不再做动态注入（Vue 端也是写死的"啊哦，颌面发育似乎不太妙！"）。
 *
 *     但当 categoryCode === 0（NORMAL，正常面容）时，Vue 端会切换到
 *     "isHealthyFace" 分支，不展示"啊哦..."警告与"坏面容"卡片，
 *     face-result.html 也要保持一致——把 .title-warning 与 .card2 隐藏。
 *     这里用 DOM 隐藏（display:none）而不是删除节点，保留 #bad-img 锚点
 *     防止 image-splitter / setImageElement 报错，并便于 Cypress 断言。
 */

/* ---------------------------------------------------------------------------
 * 小工具
 * ------------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id)

function setText(el, text) {
  if (!el) return
  el.textContent = text ?? ''
}

/**
 * 设置 <img> 的 src。
 *   - url 非空：写入 src，注入成功时元素自然显示。
 *   - url 为空：清空 src 并隐藏元素，让 .card-img-wrap 的灰色背景
 *               (--placeholder-bg) 作为占位底色露出。
 *
 * 不再需要处理 sprite 残留样式（background-size / background-position /
 * background-repeat / transform: scaleX(-1)）——这些历史包袱在切换为
 * <img> + object-fit: cover 后已不再适用。
 */
function setImageElement(el, url) {
  if (!el) return
  if (url) {
    el.onerror = null
    el.style.visibility = ''
    el.src = url
  } else {
    el.removeAttribute('src')
    el.style.visibility = 'hidden'
  }
}

function createParagraph(text) {
  const p = document.createElement('p')
  p.textContent = text
  return p
}

/* ---------------------------------------------------------------------------
 * 图片注入
 *
 * 业务约定（与 DetailAnalysisView.vue + useImageSplit 保持一致）：
 *   - predictions.futureImageUrl 作为 fullImgUrl（单张"未来面容"预测图），
 *     传入 image-splitter 后按 50% 切成左/右两半，作为好坏对比图显示在
 *     face-result.html 的 #bad-img / #good-img。
 *   - 上层数据源可直接传入预切图覆盖切割结果（goodImgUrl / badImgUrl）。
 * ------------------------------------------------------------------------ */

async function injectImages(data) {
  let { goodImgUrl, badImgUrl, fullImgUrl } = data

  if ((!goodImgUrl || !badImgUrl) && fullImgUrl) {
    try {
      const { splitFullImage } = await import('./image-splitter.js')
      const split = await splitFullImage(fullImgUrl)
      // 切割结果 left = 坏，right = 好（与 useImageSplit 一致）
      if (!badImgUrl) badImgUrl = split.badImgUrl
      if (!goodImgUrl) goodImgUrl = split.goodImgUrl
      console.log('[face-result] 已根据 fullImgUrl 切割生成左右两半')
    } catch (err) {
      console.warn('[face-result] fullImgUrl 切割失败，card-img 将保持灰色占位', err)
    }
  }

  setImageElement($('bad-img'), badImgUrl)
  setImageElement($('good-img'), goodImgUrl)
}

/* ---------------------------------------------------------------------------
 * 描述卡注入
 * ------------------------------------------------------------------------ */

function hasAnyContent(diagnosisCopy) {
  return (
    diagnosisCopy.title ||
    diagnosisCopy.opening ||
    (diagnosisCopy.body && diagnosisCopy.body.length) ||
    diagnosisCopy.careTips ||
    diagnosisCopy.habitNote
  )
}

function injectDescription(diagnosisCopy) {
  const descEl = $('description-body')
  if (!descEl) return

  if (!hasAnyContent(diagnosisCopy)) {
    console.log('[face-result] description-body 保持原始占位')
    return
  }

  setText($('desc-title'), diagnosisCopy.title)
  setText($('desc-opening'), diagnosisCopy.opening || '')

  const bodyEl = $('desc-body')
  if (bodyEl) {
    bodyEl.replaceChildren(
      ...(diagnosisCopy.body || []).map(createParagraph),
    )
  }

  const careEl = $('desc-care')
  if (careEl) {
    const hasCare = Boolean(diagnosisCopy.careTips)
    careEl.textContent = hasCare
      ? '日常护理小贴士：' + diagnosisCopy.careTips
      : ''
    careEl.style.display = hasCare ? '' : 'none'
  }

  const habitEl = $('desc-habit')
  if (habitEl) {
    const hasHabit = Boolean(diagnosisCopy.habitNote)
    habitEl.textContent = hasHabit ? diagnosisCopy.habitNote : ''
    habitEl.style.display = hasHabit ? '' : 'none'
  }
}

/* ---------------------------------------------------------------------------
 * 根据诊断编码隐藏/展示 DOM 段落 + 注入 card1 上方副标题
 *
 * 仅当 categoryCode === DiagnosisCode.NORMAL (0) 时执行隐藏 title-warning 与 card2，
 * 并把 title-after 替换为 diagnosisCopy.opening。
 * 其它编码（含 null/非法值）一律按原状展示"啊哦..."警告与 card2 坏面容卡，
 * 并保留静态副标题 "但是不用担心，矫正后面容会变成这样！"，与 Vue 端
 * DetailAnalysisView.vue 的 isHealthyFace 分支判断语义一致。
 * ------------------------------------------------------------------------ */

/** 非 NORMAL 分支的静态 fallback 副标题（含显式 <br/>） */
const DEFAULT_TITLE_AFTER_HTML =
  '但是不用担心，<br />矫正后面容会变成这样！'

function injectLayoutByCategory(data, diagnosisCopy) {
  const isNormal = data?.categoryCode === 0
  const titleWarning = document.querySelector('.title-warning')
  if (titleWarning) {
    titleWarning.style.display = isNormal ? 'none' : ''
  }
  const card2 = document.querySelector('.card.card2')
  if (card2) {
    card2.style.display = isNormal ? 'none' : ''
  }

  const titleAfter = document.getElementById('title-after')
  if (titleAfter) {
    if (isNormal && diagnosisCopy?.opening) {
      // NORMAL：使用诊断文案里的 opening（连续长句，CSS 让其自然换行）
      titleAfter.textContent = diagnosisCopy.opening
      titleAfter.classList.add('title-after--normal')
    } else {
      // 其它诊断：恢复静态 fallback（保留 <br/>）
      titleAfter.innerHTML = DEFAULT_TITLE_AFTER_HTML
      titleAfter.classList.remove('title-after--normal')
    }
  }
}

/* ---------------------------------------------------------------------------
 * 对外：注入入口
 * ------------------------------------------------------------------------ */

export async function injectAll(data, diagnosisCopy) {
  injectLayoutByCategory(data, diagnosisCopy)
  await injectImages(data)
  injectDescription(diagnosisCopy)
}
