import type { Directive } from 'vue'

/**
 * v-tap —— 触控按压反馈指令
 *
 * 为什么不直接用 :active 伪类:
 *   - iOS WKWebView / 老版本 Android WebView 上 :active 在 tap 时触发不稳定,
 *     经常「按下没反应」或「按下立刻被吞」。
 *   - 跨平台可靠做法: 直接监听 touchstart / mousedown,主动加 .is-pressed class。
 *
 * 用法:
 *   <button v-tap class="primary-btn" @click="...">...</button>
 *
 * 配合 src/assets/base.css 里的全局反馈规则 (.is-pressed / :active → opacity 0.78),
 * 加在组件 scoped 样式里自定义 :active / .is-pressed 即可加强或替换反馈效果。
 *
 * 逃生:
 *   <button v-tap data-no-tap>...</button>      // 关闭整条指令反馈
 *   <button data-no-tap-feedback>...</button>   // 仅关闭 base.css 全局反馈,
 *                                                // 但保留 v-tap 的 class 操作
 */

type Cleanup = () => void
const cleanups = new WeakMap<HTMLElement, Cleanup>()

export const vTap: Directive<HTMLElement> = {
  mounted(el) {
    if (el.dataset.noTap !== undefined) return

    const onStart = () => {
      // disabled 按钮不加 class —— 让 disabled 视觉态不受干扰
      if (el.matches(':disabled')) return
      el.classList.add('is-pressed')
    }
    const onEnd = () => el.classList.remove('is-pressed')

    // touch* 负责触屏 (移动端 / WebView)
    el.addEventListener('touchstart', onStart, { passive: true })
    el.addEventListener('touchend', onEnd)
    el.addEventListener('touchcancel', onEnd)

    // mouse* 负责桌面调试 (dev server, Chrome DevTools mobile emulation)
    el.addEventListener('mousedown', onStart)
    el.addEventListener('mouseup', onEnd)
    el.addEventListener('mouseleave', onEnd)

    // blur 处理: tab 切换 / 弹窗被强制 focus 走开时清掉残留 class
    el.addEventListener('blur', onEnd)

    cleanups.set(el, () => {
      el.removeEventListener('touchstart', onStart)
      el.removeEventListener('touchend', onEnd)
      el.removeEventListener('touchcancel', onEnd)
      el.removeEventListener('mousedown', onStart)
      el.removeEventListener('mouseup', onEnd)
      el.removeEventListener('mouseleave', onEnd)
      el.removeEventListener('blur', onEnd)
    })
  },
  unmounted(el) {
    cleanups.get(el)?.()
    cleanups.delete(el)
  },
}
