import { Outlet, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { ToastProvider } from './useToast'

/**
 * 页面公共外壳：3 个粉色背景装饰 + 内容容器（Outlet）+ 全局 Toast。
 *
 * 每个具体页面只需要把内容写在 <div className="content"> 内部，
 * 背景 / safe-area / 容器由本组件统一处理。
 */
export default function PageShell() {
  const location = useLocation()

  // 路由切换时滚到顶，避免在 face-result 里滚到底跳 phone 仍滚到底
  useEffect(() => {
    window.scrollTo(0, 0)
  }, [location.pathname])

  return (
    <ToastProvider>
      <div className="page">
        <div className="bg-deco d1" />
        <div className="bg-deco d2" />
        <div className="bg-deco d3" />
        <Outlet />
      </div>
    </ToastProvider>
  )
}
