import { apiFetch } from './apiFetch'
import type { PaymentMethod } from '../types/dashboard'

/** O11 입금 확인 — UNPAID→PAID (건별). 결제 모달은 O24 일괄 확인을 쓴다 */
export function confirmOrderPayment(orderId: number, method: PaymentMethod = 'BANK_TRANSFER') {
  return apiFetch(`/api/v1/admin/orders/${orderId}/payment`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ method }),
  })
}

/**
 * O24 테이블 일괄 입금 확인 — 그 테이블 활성 세션의 RECEIVED·UNPAID 주문을 한 번에 PAID로.
 * expectedTotal은 화면에 보여준 합계 — 서버 재계산과 다르면(그 사이 주문·취소가 있으면) 409 INVALID_STATE.
 * 대상 0건도 409. 응답 `{ orders, totalAmount }`
 */
export function confirmTablePayment(tableId: number, expectedTotal: number, method: PaymentMethod = 'BANK_TRANSFER') {
  return apiFetch('/api/v1/admin/orders/table-payment', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tableId, expectedTotal, method }),
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

/**
 * 취소복구(명세서 밖) — 취소 탭의 주문을 다시 진행(RECEIVED) 탭으로 되돌린다. 결제/환불 상태는 바뀌지 않는다.
 * 취소 상태가 아니거나 이미 삭제됐거나 모든 항목이 취소된 주문이면 409.
 */
export function restoreOrder(orderId: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/restore`, { method: 'POST' })
}

/**
 * 취소 주문 삭제(명세서 밖) — 실제 데이터 삭제가 아니라 주문현황 목록·탭 건수에서만 제외한다.
 * 결제·환불·정산 데이터는 그대로 보존된다. CANCELED가 아니거나 이미 삭제된 주문이면 409.
 */
export function deleteOrder(orderId: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}`, { method: 'DELETE' })
}

/** O14 수기 주문 — tableId 지정 시 그 테이블 세션에 귀속(없으면 자동 생성), 생략 시 테이블 미지정(M-통산) */
export function createManualOrder(items: { menuId: number; qty: number }[], tableId?: number) {
  return apiFetch('/api/v1/admin/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ tableId, items }),
  })
}

/** 결제 모달 수량 +/- — 접수+미결제 상태일 때만 가능. 증가는 서버가 품절(SOLD_OUT)·마감(ORDER_CLOSED)을 409로 거부 */
export function updateItemQty(orderId: number, itemId: number, qty: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/items/${itemId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ qty }),
  })
}

/** 결제 모달 개별 항목 취소 — 마지막 남은 항목이면 주문 전체가 취소된다 */
export function cancelItem(orderId: number, itemId: number) {
  return apiFetch(`/api/v1/admin/orders/${orderId}/items/${itemId}/cancel`, { method: 'POST' })
}

/** O15 호출 확인 — 이후 O10 calls에서 빠진다. 멱등(재호출도 200). 응답 `{ callId, acked }` */
export function ackCall(callId: number) {
  return apiFetch(`/api/v1/admin/calls/${callId}/ack`, { method: 'PATCH' })
}

/**
 * O6 퇴실·초기화 — 세션 종료(손님 토큰 즉시 410)+테이블 비움. 주문 데이터는 그대로.
 * 테이블 갈래 개정 후에는 활성 세션이 없어도 200(멱등)이고, 미결제가 있으면 응답에 warning이 실린다.
 * 개정 전 구현은 세션이 없으면 410을 내므로 호출부는 410도 "이미 비어 있음"으로 처리한다
 */
export function checkoutTable(tableId: number) {
  return apiFetch(`/api/v1/admin/tables/${tableId}/checkout`, { method: 'POST' })
}
