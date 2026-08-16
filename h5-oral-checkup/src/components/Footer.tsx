import logoLeft from '../assets/images/logo-left.png'
import logoRight from '../assets/images/logo-right.png'

/**
 * 公共底部 logo 区。
 * 视觉跟原来 .footer / .logo 区块一致：白色背景 + 两个 logo。
 *
 * Props:
 *   - noBg: input 页的 footer 跟 .content 之间没有全宽白底，仅作占位
 *           （input.css 已有 .footer 样式，会复用，这里不重复控制背景色）
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
