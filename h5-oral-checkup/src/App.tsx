import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import PageShell from './components/PageShell'
import PhoneVerifyPage from './pages/PhoneVerifyPage'
import NamePhonePage from './pages/NamePhonePage'
import FaceResultPage from './pages/FaceResultPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<PageShell />}>
          <Route index element={<Navigate to="/name" replace />} />
          <Route path="phone" element={<PhoneVerifyPage />} />
          <Route path="name" element={<NamePhonePage />} />
          <Route path="face-result" element={<FaceResultPage />} />
          <Route path="*" element={<Navigate to="/name" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}