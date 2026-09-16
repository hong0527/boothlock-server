import { Route, Routes } from 'react-router-dom'
import { CartProvider } from './context/CartContext'
import { TableOrderProvider } from './context/TableOrderContext'
import LoginPage from './pages/LoginPage'
import OrderStatusPage from './pages/OrderStatusPage'
import PartySizePage from './pages/PartySizePage'
import CartPage from './pages/customer/CartPage'
import MenuOrderPage from './pages/customer/MenuOrderPage'
import SessionExpiredPage from './pages/customer/SessionExpiredPage'
import TableSessionPage from './pages/customer/TableSessionPage'
import AccountPage from './pages/settings/AccountPage'
import MenuEditPage from './pages/settings/MenuEditPage'
import MenuListPage from './pages/settings/MenuListPage'
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
        <Route path="/orders" element={<OrderStatusPage />} />

        {/* 테이블 이용 인원 선택 — Figma 289:3672 */}
        <Route path="/party-size" element={<PartySizePage />} />
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

        {/* 소비자(손님) 주문 플로우 — API 명세서 C1~C5 */}
        <Route path="/t/:tableToken" element={<TableSessionPage />} />
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
