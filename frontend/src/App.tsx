import { Route, Routes } from 'react-router-dom'
import { MenuProvider } from './context/MenuContext'
import LoginPage from './pages/LoginPage'
import OrderStatusPage from './pages/OrderStatusPage'
import AccountPage from './pages/settings/AccountPage'
import MenuEditPage from './pages/settings/MenuEditPage'
import MenuListPage from './pages/settings/MenuListPage'
import TableQrPage from './pages/settings/TableQrPage'
import SettingsPage from './pages/SettingsPage'

function App() {
  return (
    <MenuProvider>
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/orders" element={<OrderStatusPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/settings/menu" element={<MenuListPage />} />
        <Route path="/settings/menu/new" element={<MenuEditPage />} />
        <Route path="/settings/menu/:id" element={<MenuEditPage />} />
        <Route path="/settings/account" element={<AccountPage />} />
        <Route path="/settings/table-qr" element={<TableQrPage />} />
      </Routes>
    </MenuProvider>
  )
}

export default App
