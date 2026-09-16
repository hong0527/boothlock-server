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

  it('기본 생성기는 UUID 형식', () => {
    const store = createIdempotencyKeyStore()
    expect(store.keyFor('x')).toMatch(/^[0-9a-f-]{36}$/)
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
