import logoLeft from '../assets/images/logo-left.png'
import logoRight from '../assets/images/logo-right.png'

/**
 * 公共底部 logo 区，渲染 .footer > .logo 区块。
 *
 * 由 PageShell 在每个路由的 <Outlet/> 之后统一挂载，对齐旧多页 HTML 中
 * .page > .content + .page > .footer 的兄弟结构（而非父子）。
 *
 * 样式由各 page 的 page-*.css / face-result.css 提供：
 *   - phone-verify / name-phone 用全宽白底 + 两个 logo
 *   - face-result 用全宽白底 + 两个 logo（save-btn 由 FaceResultPage 单独放在 .content 内）
 */
export default function Footer() {
  return (
    <div className="footer">
      <div className="logo">
        <img className="logo-left" src={logoLeft} alt="" />
        <img className="logo-right" src={logoRight} alt="" />
      </div>
    </div>
  )
}
