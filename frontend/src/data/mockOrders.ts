import type { OrderSummary } from '../types/dashboard'

const minutesAgo = (min: number) => new Date(Date.now() - min * 60_000).toISOString()

const items = [
  { menuId: 1, menuName: '묵은지 김치찜', unitPrice: 28000, qty: 1 },
  { menuId: 2, menuName: '계란찜', unitPrice: 6000, qty: 1 },
]

/** 실제 API(GET /admin/orders) 연동 전까지 쓰는 목업 — 응답 형태는 DashboardResponse.OrderSummary와 동일 */
export const mockOrders: OrderSummary[] = [
  { orderId: 1, orderNo: 'A1-1', status: 'RECEIVED', items: [...items], createdAt: minutesAgo(15), tableLabel: '1번 테이블' },
  { orderId: 2, orderNo: 'A1-2', status: 'RECEIVED', items: [...items], createdAt: minutesAgo(9), tableLabel: '1번 테이블' },
  { orderId: 3, orderNo: 'A1-3', status: 'RECEIVED', items: [...items], createdAt: minutesAgo(2), tableLabel: '1번 테이블' },
  { orderId: 4, orderNo: 'A1-4', status: 'DONE', items: [...items], createdAt: minutesAgo(40), tableLabel: '1번 테이블' },
  { orderId: 5, orderNo: 'A1-5', status: 'DONE', items: [...items], createdAt: minutesAgo(38), tableLabel: '1번 테이블' },
  { orderId: 6, orderNo: 'A1-6', status: 'DONE', items: [...items], createdAt: minutesAgo(35), tableLabel: '1번 테이블' },
  { orderId: 7, orderNo: 'A1-7', status: 'DONE', items: [...items], createdAt: minutesAgo(30), tableLabel: '1번 테이블' },
  { orderId: 8, orderNo: 'A1-8', status: 'DONE', items: [...items], createdAt: minutesAgo(25), tableLabel: '1번 테이블' },
  { orderId: 9, orderNo: 'A1-9', status: 'CANCELED', items: [...items], createdAt: minutesAgo(50), tableLabel: '1번 테이블' },
  { orderId: 10, orderNo: 'A1-10', status: 'CANCELED', items: [...items], createdAt: minutesAgo(45), tableLabel: '1번 테이블' },
]
