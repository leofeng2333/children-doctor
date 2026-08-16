import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'

export type ToastType = 'success' | 'error' | undefined

interface ToastState {
  msg: string
  type: ToastType
  key: number
}

interface ToastApi {
  show: (msg: string, type?: ToastType) => void
}

const ToastCtx = createContext<ToastApi | null>(null)

let externalShow: ((msg: string, type?: ToastType) => void) | null = null

/**
 * 在组件外调用 showToast(msg, type)，等价于旧的命令式 showToast。
 * 用法（迁移期便利）：
 *   import { showToast } from '@/components/useToast'
 *   showToast('请输入手机号', 'error')
 */
export function showToast(msg: string, type: ToastType = undefined) {
  if (externalShow) externalShow(msg, type)
  else console.warn('[toast] ToastHost 尚未挂载，丢弃消息:', msg)
}

const TOAST_TTL_MS = 2200

export function ToastProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ToastState | null>(null)
  const timerRef = useRef<number | null>(null)

  const show = useCallback((msg: string, type: ToastType = undefined) => {
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current)
      timerRef.current = null
    }
    // 用 key++ 强制 remount，让 transition 重新触发（对齐旧 toast.js 的 reflow 技巧）
    setState({ msg, type, key: Date.now() + Math.random() })
    timerRef.current = window.setTimeout(() => {
      setState(null)
      timerRef.current = null
    }, TOAST_TTL_MS)
  }, [])

  useEffect(() => {
    externalShow = show
    return () => {
      externalShow = null
    }
  }, [show])

  return (
    <ToastCtx.Provider value={{ show }}>
      {children}
      {state && <ToastBox key={state.key} msg={state.msg} type={state.type} />}
    </ToastCtx.Provider>
  )
}

function ToastBox({ msg, type }: { msg: string; type: ToastType }) {
  const cls = `toast show${type ? ` ${type}` : ''}`
  return <div className={cls}>{msg}</div>
}

/** 在子组件内拿到 show 函数（更 React-y 的用法）。 */
export function useToast() {
  const ctx = useContext(ToastCtx)
  if (!ctx) throw new Error('[useToast] 必须包在 <ToastProvider> 内')
  return ctx.show
}
