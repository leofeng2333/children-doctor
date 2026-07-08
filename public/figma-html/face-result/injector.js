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
 *   - 整张图 generatedImageUrls[0] 经 canvas 50% 切割：
 *       左半 [0, w/2)   = 矫正前（坏面容）→ badImgUrl
 *       右半 [w/2, w)   = 矫正后（好面容）→ goodImgUrl
 *   - 上层数据源可直接传入预切图覆盖切割结果。
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
 * 对外：注入入口
 * ------------------------------------------------------------------------ */

export async function injectAll(data, diagnosisCopy) {
  await injectImages(data)
  injectDescription(diagnosisCopy)
}
