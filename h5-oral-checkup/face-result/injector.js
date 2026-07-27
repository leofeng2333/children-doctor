/**
 * DOM 注入
 *
 * 负责把 ResultShape + DiagnosisCopy 渲染到页面上的固定锚点。
 *
 * 当 categoryCode === 0（NORMAL）时隐藏 title-warning 与 card2，
 * 并把 title-after 替换为 diagnosisCopy.opening。
 */

const $ = (id) => document.getElementById(id)

function setText(el, text) {
  if (!el) return
  el.textContent = text ?? ''
}

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

/* 图片注入 */
async function injectImages(data) {
  let { goodImgUrl, badImgUrl, fullImgUrl } = data

  if ((!goodImgUrl || !badImgUrl) && fullImgUrl) {
    try {
      const { splitFullImage } = await import('./image-splitter.js')
      const split = await splitFullImage(fullImgUrl)
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

/* 描述卡注入 */
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

/* 根据诊断编码隐藏/展示 DOM 段落 + 注入 card1 上方副标题 */
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
      titleAfter.textContent = diagnosisCopy.opening
      titleAfter.classList.add('title-after--normal')
    } else {
      titleAfter.innerHTML = DEFAULT_TITLE_AFTER_HTML
      titleAfter.classList.remove('title-after--normal')
    }
  }
}

export async function injectAll(data, diagnosisCopy) {
  injectLayoutByCategory(data, diagnosisCopy)
  await injectImages(data)
  injectDescription(diagnosisCopy)
}