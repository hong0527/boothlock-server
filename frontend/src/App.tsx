import { Route, Routes } from 'react-router-dom'
import { TableOrderProvider } from './context/TableOrderContext'
import LoginPage from './pages/LoginPage'
import OrderStatusPage from './pages/OrderStatusPage'
import TableHomePage from './pages/TableHomePage'

function App() {
  return (
    <TableOrderProvider>
      <Routes>
        <Route path="/" element={<LoginPage />} />
        <Route path="/orders" element={<OrderStatusPage />} />
        <Route path="/tables" element={<TableHomePage />} />
      </Routes>
    </TableOrderProvider>
  )
}

export default App
