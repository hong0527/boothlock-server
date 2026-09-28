import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'

// 코드 리뷰(2026-09-29) 발견: CartProvider는 앱 최상단에 한 번만 마운트돼 장바구니를 메모리(React state)에도
// 들고 있다 — 풀 리로드 없이 세션 토큰만 바뀌는 경로가 생기면, 저장소만 지워서는 화면에 앞 손님 장바구니가
// 그대로 남는다. onCartCleared 구독자가 그 순간 같이 불리는지를 CartContext 없이 직접 확인한다.
//
// node 테스트 환경엔 window가 없다(이 프로젝트는 jsdom을 안 쓰고 필요한 화면만 vi.stubGlobal로 세운다,
// OrderStatusPage.test.tsx와 같은 방식) — onCartCleared·notifyCartCleared는 window.dispatchEvent를 쓰므로
// 실제 addEventListener/removeEventListener/dispatchEvent를 갖춘 EventTarget을 window로 세운다.
// localStorage는 일부러 세우지 않는다 — safeStorage가 참조 실패를 잡아 메모리 폴백으로 넘어가는지도 같이 확인된다
vi.stubGlobal('window', new EventTarget())
afterAll(() => { vi.unstubAllGlobals() })

describe('onCartCleared — 세션이 바뀌거나 끝날 때 장바구니 메모리 상태도 같이 비우는 신호', () => {
  // 모듈 메모리 상태(safeStorage의 memory 폴백)가 다음 테스트로 새지 않게 매번 clearCustomerSession으로 비운다
  beforeEach(async () => {
    const { clearCustomerSession } = await import('./customerSession')
    clearCustomerSession()
  })

  it('setCustomerSession으로 토큰이 실제로 바뀌면(재스캔 등) 구독자가 불린다', async () => {
    const { setCustomerSession, onCartCleared } = await import('./customerSession')
    const handler = vi.fn()
    const stop = onCartCleared(handler)
    try {
      setCustomerSession('token-a', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
      expect(handler).not.toHaveBeenCalled() // 첫 세션(이전 토큰 없음)은 "바뀐" 게 아니다
      setCustomerSession('token-b', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
      expect(handler).toHaveBeenCalledTimes(1)
    } finally {
      stop()
    }
  })

  it('같은 토큰으로 다시 불러도(복원·인원수 갱신 등) 구독자가 불리지 않는다', async () => {
    const { setCustomerSession, onCartCleared } = await import('./customerSession')
    const handler = vi.fn()
    const stop = onCartCleared(handler)
    try {
      setCustomerSession('token-a', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
      setCustomerSession('token-a', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0, partySize: 2 })
      expect(handler).not.toHaveBeenCalled()
    } finally {
      stop()
    }
  })

  it('clearCustomerSession(퇴실·만료)에서도 구독자가 불린다', async () => {
    const { setCustomerSession, clearCustomerSession, onCartCleared } = await import('./customerSession')
    const handler = vi.fn()
    const stop = onCartCleared(handler)
    try {
      setCustomerSession('token-a', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
      clearCustomerSession()
      expect(handler).toHaveBeenCalledTimes(1)
    } finally {
      stop()
    }
  })

  it('해제 함수를 부르면 더 이상 불리지 않는다', async () => {
    const { setCustomerSession, onCartCleared } = await import('./customerSession')
    const handler = vi.fn()
    const stop = onCartCleared(handler)
    stop()
    setCustomerSession('token-a', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
    setCustomerSession('token-b', { boothName: '부스', boothIsOpen: true, tableLabel: 'A-1', seatFeeCharged: false, seatFeePerPerson: 0 })
    expect(handler).not.toHaveBeenCalled()
  })
})
