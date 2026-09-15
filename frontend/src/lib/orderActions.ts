import { apiFetch } from './apiFetch'

/** O11 입금 확인 — UNPAID→PAID */
export function confirmOrderPayment(orderId: number, method: 'BANK_TRANSFER' | 'CASH' = 'BANK_TRANSFER') {
  return apiFetch(`/api/v1/admin/orders/${orderId}/payment`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ method }),
  })
}

/** O12 완료 처리 — RECEIVED→DONE */
export function completeOrder(orderId: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/complete`, { method: 'PATCH' })
}

/** O13 운영자 취소 — 사유 입력 UI가 없어서 비워 보내면 서버가 기본 사유로 채운다 */
export function cancelOrder(orderId: number, reason?: string) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason ?? '' }),
  })
}

/** O14 수기 주문 — tableId 지정 시 그 테이블 세션에 귀속(없으면 자동 생성), 생략 시 테이블 미지정(M-통산) */
export function createManualOrder(items: { menuId: number; qty: number }[], tableId?: number) {
  return apiFetch('/api/v1/admin/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tableId, items }),
  })
}

/** O6 결제 모달 수량 +/- — 접수+미결제 상태일 때만 가능 */
export function updateItemQty(orderId: number, itemId: number, qty: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/items/${itemId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ qty }),
  })
}

/** O6 결제 모달 개별 항목 취소 — 마지막 남은 항목이면 주문 전체가 취소된다 */
export function cancelItem(orderId: number, itemId: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/items/${itemId}/cancel`, { method: 'POST' })
}

/** O6 퇴실·초기화("결제 완료") — 세션 종료+테이블 비움. 이미 퇴실 처리됐으면 410 */
export function checkoutTable(tableId: number) {
  return apiFetch(`/api/v1/admin/tables/${tableId}/checkout`, { method: 'POST' })
}
