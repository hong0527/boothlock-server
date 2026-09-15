import { Route, Routes } from 'react-router-dom'
import { TableOrderProvider } from './context/TableOrderContext'
import LoginPage from './pages/LoginPage'
import OrderStatusPage from './pages/OrderStatusPage'
import AccountPage from './pages/settings/AccountPage'
import MenuEditPage from './pages/settings/MenuEditPage'
import MenuListPage from './pages/settings/MenuListPage'
import TableQrPage from './pages/settings/TableQrPage'
import SettingsPage from './pages/SettingsPage'
import TableHomePage from './pages/TableHomePage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/orders" element={<OrderStatusPage />} />
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
    </Routes>
  )
}

export default App
