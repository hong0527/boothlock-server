import { Route, Routes } from 'react-router-dom'
import { CartProvider } from './context/CartContext'
import { TableOrderProvider } from './context/TableOrderContext'
import { SIGNUP_ENABLED } from './lib/featureFlags'
import LoginPage from './pages/LoginPage'
import SignupPage from './pages/SignupPage'
import CodeScanPage from './pages/CodeScanPage'
import HomePage from './pages/HomePage'
import OrderStatusPage from './pages/OrderStatusPage'
import PartySizePage from './pages/PartySizePage'
import CartPage from './pages/customer/CartPage'
import MenuOrderPage from './pages/customer/MenuOrderPage'
import SessionExpiredPage from './pages/customer/SessionExpiredPage'
import TableSessionPage from './pages/customer/TableSessionPage'
import AccountPage from './pages/settings/AccountPage'
import MenuEditPage from './pages/settings/MenuEditPage'
import MenuListPage from './pages/settings/MenuListPage'
import SettlementPage from './pages/settings/SettlementPage'
import TableQrPage from './pages/settings/TableQrPage'
import SettingsPage from './pages/SettingsPage'
import TableHomePage from './pages/TableHomePage'
import OrderConfirmPage from './pages/customer/OrderConfirmPage'
import PaymentInfoPage from './pages/customer/PaymentInfoPage'
import OrderHistoryPage from './pages/customer/OrderHistoryPage'

function App() {
  return (
    <CartProvider>
      <Routes>
        <Route path="/" element={<LoginPage />} />
        {/* 임시 데모 (Figma 237:344) — 기본 꺼짐, VITE_SIGNUP_ENABLED=true일 때만 노출. SignupPage.tsx 상단 주석 참고 */}
        {SIGNUP_ENABLED && <Route path="/signup" element={<SignupPage />} />}
        <Route path="/orders" element={<OrderStatusPage />} />

        {/* 방문자 홈 — Figma 92:2 / 95:368 / 212:704 */}
        <Route path="/home" element={<HomePage />} />
        <Route path="/scan" element={<CodeScanPage />} />
        <Route
          path="/tables"
          element={
            <TableOrderProvider>
              <TableHomePage />
            </TableOrderProvider>
          }
        />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/settings/menu" element={<MenuListPage />} />
        <Route path="/settings/menu/new" element={<MenuEditPage />} />
        <Route path="/settings/menu/:id" element={<MenuEditPage />} />
        <Route path="/settings/account" element={<AccountPage />} />
        <Route path="/settings/table-qr" element={<TableQrPage />} />
        {/* Figma 디자인 없음 (파일럿 스코프 밖 화면) — O19 ADMIN 전용, 백엔드 403으로도 막힘 */}
        <Route path="/settings/settlement" element={<SettlementPage />} />

        {/* 소비자(손님) 주문 플로우 — API 명세서 C1~C5 */}
        <Route path="/t/:tableToken" element={<TableSessionPage />} />
        {/* 세션 발급 직후 인원 선택 — Figma 289:3672 */}
        <Route path="/party-size" element={<PartySizePage />} />
        <Route path="/order" element={<MenuOrderPage />} />
        <Route path="/cart" element={<CartPage />} />
        <Route path="/session-expired" element={<SessionExpiredPage />} />

        <Route path="/order-confirm" element={<OrderConfirmPage />} />
        <Route path="/payment-info" element={<PaymentInfoPage />} />
        <Route path="/order-history" element={<OrderHistoryPage />} />
      </Routes>
    </CartProvider>
  )
}

export default App
