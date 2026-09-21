import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isStorageUsable, readStored, readStoredJson, removeStored, writeStored } from './safeStorage'

const KEY = 'boothlock_test_key'
const OTHER_KEY = 'boothlock_test_key_2'
const PROBE_KEY = '__boothlock_storage_probe__'

// jsdom을 붙이지 않는다 — 이 프로젝트는 DOM 의존성 없이 테스트한다(AccountPage.test.tsx와 같은 방침).
// Storage 인터페이스 중 safeStorage가 쓰는 세 메서드만 있으면 충분하다.
class FakeStorage {
  private map = new Map<string, string>()
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) as string) : null
  }
  setItem(key: string, value: string) {
    this.map.set(key, String(value))
  }
  removeItem(key: string) {
    this.map.delete(key)
  }
}

let fake: FakeStorage

beforeEach(() => {
  fake = new FakeStorage()
  vi.stubGlobal('localStorage', fake)
})

afterEach(() => {
  vi.restoreAllMocks()
  // 모듈 수준 메모리 폴백은 테스트 사이에 남으므로 명시적으로 비운다
  removeStored(KEY)
  removeStored(OTHER_KEY)
  vi.unstubAllGlobals()
})

describe('정상 브라우저', () => {
  it('쓰고 읽는다', () => {
    writeStored(KEY, 'v1')
    expect(readStored(KEY)).toBe('v1')
    expect(fake.getItem(KEY)).toBe('v1')
  })

  it('지우면 없다', () => {
    writeStored(KEY, 'v1')
    removeStored(KEY)
    expect(readStored(KEY)).toBeNull()
  })

  it('없는 키는 null', () => {
    expect(readStored(OTHER_KEY)).toBeNull()
  })
})

describe('저장이 조용히 무시되는 브라우저 (iOS Safari 실측 — 운영 사고 재현)', () => {
  it('setItem이 예외 없이 무시돼도 같은 탭에서는 값이 읽힌다', () => {
    // 던지지도 않고 저장도 안 한다 — try/catch만으로는 못 잡는 실패 방식이라 이 사고가 났다
    vi.spyOn(fake, 'setItem').mockImplementation(() => {})

    writeStored(KEY, 'session-token')

    expect(fake.getItem(KEY)).toBeNull() // 실제로 저장은 안 됐고
    expect(readStored(KEY)).toBe('session-token') // 그래도 주문 흐름은 이어진다
  })

  it('지우면 메모리 폴백에서도 사라진다', () => {
    vi.spyOn(fake, 'setItem').mockImplementation(() => {})
    writeStored(KEY, 'session-token')
    removeStored(KEY)
    expect(readStored(KEY)).toBeNull()
  })
})

describe('접근 자체가 막힌 브라우저 (쿠키 차단·저장공간 부족)', () => {
  it('setItem이 던져도 흐름이 끊기지 않는다', () => {
    vi.spyOn(fake, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })

    expect(() => writeStored(KEY, 'session-token')).not.toThrow()
    expect(readStored(KEY)).toBe('session-token')
  })

  it('getItem이 던져도 메모리에서 읽는다', () => {
    writeStored(KEY, 'session-token')
    vi.spyOn(fake, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })

    expect(readStored(KEY)).toBe('session-token')
  })

  it('removeItem이 던져도 터지지 않는다', () => {
    vi.spyOn(fake, 'removeItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })

    expect(() => removeStored(KEY)).not.toThrow()
  })
})

describe('readStoredJson', () => {
  it('정상 JSON을 파싱한다', () => {
    writeStored(KEY, JSON.stringify({ boothName: '컴공주점' }))
    expect(readStoredJson<{ boothName: string }>(KEY)).toEqual({ boothName: '컴공주점' })
  })

  it('깨진 JSON은 화면을 죽이지 않고 null (렌더 중에 호출되는 자리가 있다)', () => {
    fake.setItem(KEY, '{깨진')
    expect(() => readStoredJson(KEY)).not.toThrow()
    expect(readStoredJson(KEY)).toBeNull()
  })

  it('깨진 값은 지워서 새로고침해도 계속 깨지지 않게 한다', () => {
    fake.setItem(KEY, '{깨진')
    readStoredJson(KEY)
    expect(fake.getItem(KEY)).toBeNull()
  })

  it('값이 없으면 null', () => {
    expect(readStoredJson(OTHER_KEY)).toBeNull()
  })
})

describe('isStorageUsable', () => {
  it('정상 브라우저면 true', () => {
    expect(isStorageUsable()).toBe(true)
  })

  it('쓰기가 조용히 무시되면 false — 되읽어서 확인하기 때문', () => {
    vi.spyOn(fake, 'setItem').mockImplementation(() => {})
    expect(isStorageUsable()).toBe(false)
  })

  it('접근이 막혀 던지면 false', () => {
    vi.spyOn(fake, 'setItem').mockImplementation(() => {
      throw new Error('SecurityError')
    })
    expect(isStorageUsable()).toBe(false)
  })

  it('확인용 키를 남기지 않는다', () => {
    isStorageUsable()
    expect(fake.getItem(PROBE_KEY)).toBeNull()
  })
})
