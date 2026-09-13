import { Outlet, useLocation } from 'react-router-dom'
import { useEffect } from 'react'
import { ToastProvider } from './useToast'

/**
 * 页面公共外壳：3 个粉色背景装饰 + 内容容器（Outlet）。
 *
 * 每个具体页面只需要把内容写在 <div className="content"> 内部，
 * 背景 / safe-area / 容器由本组件统一处理。
 *
 * 底部 logo 区只 face-result 页面需要（透明背景，紧贴内容），由 FaceResultPage
 * 自己渲染 .footer > .logo。其他页面（name-phone / phone-verify）不再挂 footer。
 *
 * 关键结构与旧多页 HTML 完全一致：
 *   .page { display: flex; flex-direction: column }
 *     .bg-deco d1/d2/d3
 *     .content              ← 与 footer 同级（不是父子）
 *     .footer               ← 仅 face-result 内 .content 之后渲染
 *
 * 之前版本把 <Footer/> 放进每个 page 的 .content 内部，导致：
 *   - footer 跟随 .content 排版，logo 白条不再"贴屏底"
 *   - name/phone 页的 footer 出现在 form 下方而不是 .content 之后
 *   - face-result 页的 save-btn 与 logo 一起在 content 内部（部分 OK 但不一致）
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
