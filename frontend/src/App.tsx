import { Route, Routes } from 'react-router-dom'
import LoginPage from './pages/LoginPage'
import OrderStatusPage from './pages/OrderStatusPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/orders" element={<OrderStatusPage />} />
    </Routes>
  )
}

export default App
