import { useEffect, useRef, useState, useCallback } from 'react'

interface CountdownApi {
  remaining: number
  isRunning: boolean
  start: (seconds: number) => void
  cancel: () => void
}

/**
 * 倒计时控制器（React 版）。
 * 用法：
 *   const cd = useCountdown()
 *   cd.start(60)   // 启动 60s 倒计时
 *   cd.cancel()    // 立刻停止
 *   cd.isRunning   // 当前是否在倒计时
 *
 * 渲染文案由调用方决定，本 hook 不耦合 DOM；让 UI 更纯。
 */
export function useCountdown(): CountdownApi {
  const [remaining, setRemaining] = useState(0)
  const timerRef = useRef<number | null>(null)
  const isRunning = remaining > 0

  const clear = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current)
      timerRef.current = null
    }
  }, [])

  const start = useCallback(
    (seconds: number) => {
      clear()
      setRemaining(seconds)
      timerRef.current = window.setInterval(() => {
        setRemaining((prev) => {
          if (prev <= 1) {
            clear()
            return 0
          }
          return prev - 1
        })
      }, 1000)
    },
    [clear],
  )

  const cancel = useCallback(() => {
    clear()
    setRemaining(0)
  }, [clear])

  useEffect(() => {
    return () => clear()
  }, [clear])

  return { remaining, isRunning, start, cancel }
}
