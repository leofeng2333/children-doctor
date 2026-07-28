/**
 * 获取验证码按钮的 60s 倒计时控制器
 *
 * 用法：
 *   const ctrl = createCountdown(getCodeBtn)
 *   ctrl.start(60)    // 启动 / 重置 60 秒倒计时
 *   ctrl.cancel()     // 立刻停止（用于请求失败回滚）
 *   ctrl.isRunning()  // 当前是否在倒计时
 */

export function createCountdown(buttonEl) {
  let remaining = 0
  let timer = null

  function render() {
    if (remaining <= 0) {
      buttonEl.disabled = false
      buttonEl.classList.remove('sending')
      buttonEl.textContent = '获取验证码'
      return
    }
    buttonEl.disabled = true
    buttonEl.classList.add('sending')
    buttonEl.textContent = `发送中，${remaining}s后可重试`
  }

  return {
    start(seconds) {
      remaining = seconds
      render()
      if (timer) clearInterval(timer)
      timer = setInterval(() => {
        remaining -= 1
        if (remaining <= 0) {
          clearInterval(timer)
          timer = null
        }
        render()
      }, 1000)
    },
    cancel() {
      if (timer) {
        clearInterval(timer)
        timer = null
      }
      remaining = 0
      render()
    },
    isRunning() {
      return remaining > 0
    },
  }
}
