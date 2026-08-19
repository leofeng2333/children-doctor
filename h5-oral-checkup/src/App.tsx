import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import PageShell from './components/PageShell'
import PhoneVerifyPage from './pages/PhoneVerifyPage'
import NamePhonePage from './pages/NamePhonePage'
import FaceResultPage from './pages/FaceResultPage'

/**
 * 部署子路径：https://m.hangxiaoya.com/zt/input/
 * - vite base = '/zt/input/' （vite.config.ts），所有静态资源（JS/CSS）都从该路径出发
 * - React Router basename 必须与 vite base 一致，否则浏览器地址 vs SPA 路由会失配
 *   （例：访问 /zt/input/ 时 basename='/zt/input' 让 Router 认为 path='/'，index 路由生效）
 *
 * 路由表（用户感知到的路径）：
 *   /zt/input/         → 重定向到 /zt/input/phone   （首页默认进手机号+验证码 default 模式）
 *   /zt/input/phone    → PhoneVerifyPage （手机号+验证码 default 模式）
 *   /zt/input/name     → NamePhonePage （姓名+手机号 first 模式）
 *   /zt/input/face-result → FaceResultPage （结果页）
 *   其它               → 重定向到 /zt/input/phone
 */
export default function App() {
  return (
    <BrowserRouter basename="/zt/input">
      <Routes>
        <Route element={<PageShell />}>
          <Route index element={<Navigate to="/phone" replace />} />
          <Route path="phone" element={<PhoneVerifyPage />} />
          <Route path="name" element={<NamePhonePage />} />
          <Route path="face-result" element={<FaceResultPage />} />
          <Route path="*" element={<Navigate to="/phone" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}