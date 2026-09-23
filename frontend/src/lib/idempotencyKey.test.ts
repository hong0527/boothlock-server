import { describe, expect, it } from 'vitest'
import { cartFingerprint, createIdempotencyKeyStore } from './idempotencyKey'

const counterGenerator = () => {
  let n = 0
  return () => `key-${++n}`
}

describe('createIdempotencyKeyStore', () => {
  it('같은 장바구니로 다시 시도하면 같은 키를 돌려준다 (실패 후 재클릭 = 중복 주문 방지)', () => {
    const store = createIdempotencyKeyStore(counterGenerator())
    expect(store.keyFor('1x2')).toBe('key-1')
    expect(store.keyFor('1x2')).toBe('key-1')
  })

  it('성공 후 clear하면 다음 주문은 새 키', () => {
    const store = createIdempotencyKeyStore(counterGenerator())
    expect(store.keyFor('1x2')).toBe('key-1')
    store.clear()
    expect(store.keyFor('1x2')).toBe('key-2')
  })

  it('장바구니 내용이 바뀌면 새 키 (다른 주문이므로 서버 멱등 응답을 받으면 안 됨)', () => {
    const store = createIdempotencyKeyStore(counterGenerator())
    expect(store.keyFor('1x2')).toBe('key-1')
    expect(store.keyFor('1x3')).toBe('key-2')
    // 원래 내용으로 되돌려도 이전 키로 돌아가지 않는다(단순 규칙 — 마지막 서명만 기억)
    expect(store.keyFor('1x2')).toBe('key-3')
  })

  it('기본 생성기는 32자 16진수 (HTTP 비보안 컨텍스트에서도 동작해야 하므로 randomUUID 대신 getRandomValues 사용)', () => {
    const store = createIdempotencyKeyStore()
    expect(store.keyFor('x')).toMatch(/^[0-9a-f]{32}$/)
  })
})

describe('cartFingerprint', () => {
  it('메뉴·수량이 같으면 순서가 달라도 같은 서명', () => {
    expect(cartFingerprint([{ menuId: 2, qty: 1 }, { menuId: 1, qty: 3 }])).toBe(
      cartFingerprint([{ menuId: 1, qty: 3 }, { menuId: 2, qty: 1 }]),
    )
  })

  it('수량이 다르면 다른 서명', () => {
    expect(cartFingerprint([{ menuId: 1, qty: 1 }])).not.toBe(cartFingerprint([{ menuId: 1, qty: 2 }]))
  })
})

describe('customerOrderKeys (손님 주문 키 — 화면이 다시 떠도 유지)', () => {
  it('모듈 단위라 다시 import해도 같은 키 — 뒤로 갔다 와도 재시도가 중복 주문이 되지 않는다', async () => {
    const { customerOrderKeys: a, customerOrderFingerprint } = await import('./idempotencyKey')
    a.clear()
    const fp = customerOrderFingerprint('tok', [{ menuId: 1, qty: 2 }])
    const first = a.keyFor(fp)
    const { customerOrderKeys: b } = await import('./idempotencyKey')
    expect(b.keyFor(fp)).toBe(first)
  })

  it('세션이 다르면 같은 장바구니여도 새 키', async () => {
    const { customerOrderKeys, customerOrderFingerprint } = await import('./idempotencyKey')
    customerOrderKeys.clear()
    const items = [{ menuId: 1, qty: 2 }]
    const k1 = customerOrderKeys.keyFor(customerOrderFingerprint('tok-a', items))
    expect(customerOrderKeys.keyFor(customerOrderFingerprint('tok-b', items))).not.toBe(k1)
  })

  it('clearCustomerSession(퇴실·만료)이 진행 중 키를 버린다', async () => {
    const { customerOrderKeys, customerOrderFingerprint } = await import('./idempotencyKey')
    const { clearCustomerSession } = await import('./customerSession')
    customerOrderKeys.clear()
    const fp = customerOrderFingerprint('tok', [{ menuId: 1, qty: 2 }])
    const k1 = customerOrderKeys.keyFor(fp)
    clearCustomerSession()
    expect(customerOrderKeys.keyFor(fp)).not.toBe(k1)
  })
})

describe('재시도 간격(RETRY_WINDOW_MS) — 옛 키로 새 주문이 사라지지 않게', () => {
  it('마지막 사용 뒤 3분 안이면 같은 키(재시도), 넘으면 새 키(새 주문)', async () => {
    const { RETRY_WINDOW_MS } = await import('./idempotencyKey')
    let t = 0
    const store = createIdempotencyKeyStore(counterGenerator(), () => t)
    expect(store.keyFor('1x1')).toBe('key-1')
    t += RETRY_WINDOW_MS
    expect(store.keyFor('1x1')).toBe('key-1')
    // 재시도할 때마다 창이 연장된다 — 연타 중에 키가 바뀌면 안 된다
    t += RETRY_WINDOW_MS
    expect(store.keyFor('1x1')).toBe('key-1')
    t += RETRY_WINDOW_MS + 1
    expect(store.keyFor('1x1')).toBe('key-2')
  })
})
