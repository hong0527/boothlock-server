import { Children, isValidElement, type ReactNode } from 'react'
import { afterAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../lib/apiFetch'
import { clearAuth } from '../../lib/auth'
import BoothNamePage from './BoothNamePage'

// DOM 의존성 추가 없이 페이지의 초기화·입력·submit을 실행하는 최소 hook harness (AccountPage.test.tsx와 같은 방식).
const hooks = vi.hoisted(() => ({ values: [] as unknown[], cursor: 0, mounted: false, effects: [] as (() => void)[] }))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = initial
    return [hooks.values[index], (value: unknown) => { hooks.values[index] = value }]
  },
  useEffect: (effect: () => void) => { if (!hooks.mounted) hooks.effects.push(effect) },
}))
vi.mock('../../lib/apiFetch', () => ({ apiFetch: vi.fn() }))

type Props = {
  children?: ReactNode
  label?: string
  value?: string
  maxLength?: number
  disabled?: boolean
  onChange?: (event: { target: { value: string } }) => void
  onSubmit?: (event: { preventDefault: () => void }) => Promise<void>
}
function render() {
  hooks.cursor = 0
  const tree = BoothNamePage()
  hooks.mounted = true
  hooks.effects.splice(0).forEach(effect => effect())
  return tree
}
function find(node: ReactNode, predicate: (props: Props) => boolean): Props | undefined {
  for (const child of Children.toArray(node)) {
    if (!isValidElement<Props>(child)) continue
    if (predicate(child.props)) return child.props
    const nested = find(child.props.children, predicate)
    if (nested) return nested
  }
}
function field() { return find(render(), props => props.label === '점포명')! }
function edit(value: string) { field().onChange!({ target: { value } }) }
async function submit() {
  await find(render(), props => !!props.onSubmit)!.onSubmit!({ preventDefault() {} })
}

// vitest 기본 환경(node)에는 localStorage가 없다 — auth.ts가 쓰는 최소 동작만 세운다
const store = new Map<string, string>()
vi.stubGlobal('localStorage', {
  getItem: (key: string) => store.get(key) ?? null,
  setItem: (key: string, value: string) => { store.set(key, value) },
  removeItem: (key: string) => { store.delete(key) },
  clear: () => { store.clear() },
})

const STAFF_KEY = 'boothlock_staff'
let stored: { name: string }
let payloads: Record<string, unknown>[]

async function load(name: string) {
  stored = { name }
  vi.mocked(apiFetch).mockImplementation(async (_path, options) => {
    if (options?.method === 'PATCH') {
      const payload = JSON.parse(String(options.body))
      payloads.push(payload)
      // 서버의 name 계약을 모사한다 — 공백만/50자 초과는 400. 실제 백엔드 테스트는 별도로 돈다.
      const value = String(payload.name ?? '')
      if (!value.trim() || value.length > 50) {
        return new Response(JSON.stringify({ error: { code: 'INVALID_REQUEST', message: 'name 형식이 올바르지 않습니다.' } }), { status: 400 })
      }
      stored = { name: value }
    }
    return new Response(JSON.stringify(stored), { status: 200 })
  })
  render()
  await vi.waitFor(() => expect(field().disabled).toBe(false))
}

// 파일별로 환경이 분리되지만(vitest 기본 isolate), 나중에 그 설정이 바뀌어도 새지 않게 되돌린다
afterAll(() => { vi.unstubAllGlobals() })

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  payloads = []
  // safeStorage는 저장이 막힌 브라우저에 대비해 메모리 사본도 들고 있다 —
  // localStorage만 비우면 그 사본이 남아 다음 테스트로 새어 나간다. clearAuth가 둘 다 지운다.
  store.clear()
  clearAuth()
  vi.clearAllMocks()
})

describe('BoothNamePage', () => {
  it('지금 부스명을 불러와 입력란에 채운다', async () => {
    await load('컴공 주점')
    expect(field().value).toBe('컴공 주점')
  })

  it('바꾼 이름을 name 필드만 담아 보낸다', async () => {
    await load('컴공 주점')
    edit('소웨 주점')
    await submit()
    expect(payloads[0]).toEqual({ name: '소웨 주점' })
    expect(stored.name).toBe('소웨 주점')
  })

  it('앞뒤 공백을 떼고 보낸다', async () => {
    await load('컴공 주점')
    edit('  소웨 주점  ')
    await submit()
    expect(payloads[0]).toEqual({ name: '소웨 주점' })
  })

  it('공백만 입력하면 요청을 보내지 않는다', async () => {
    await load('컴공 주점')
    edit('   ')
    await submit()
    expect(payloads).toHaveLength(0)
    expect(JSON.stringify(render())).toContain('점포명을 입력해주세요')
  })

  it('50자를 넘으면 요청을 보내지 않는다', async () => {
    await load('컴공 주점')
    edit('가'.repeat(51))
    await submit()
    expect(payloads).toHaveLength(0)
    expect(JSON.stringify(render())).toContain('50자까지')
  })

  it('저장에 성공하면 저장된 부스명도 함께 갱신한다', async () => {
    localStorage.setItem(STAFF_KEY, JSON.stringify({ role: 'ADMIN', boothId: 1, boothName: '컴공 주점' }))
    await load('컴공 주점')
    edit('소웨 주점')
    await submit()
    expect(JSON.parse(localStorage.getItem(STAFF_KEY)!)).toEqual({ role: 'ADMIN', boothId: 1, boothName: '소웨 주점' })
  })

  it('로그인 정보가 없어도 저장이 깨지지 않는다', async () => {
    await load('컴공 주점')
    edit('소웨 주점')
    await submit()
    expect(stored.name).toBe('소웨 주점')
    expect(localStorage.getItem(STAFF_KEY)).toBeNull()
  })

  it('서버가 거절하면 그 이유를 그대로 보여준다', async () => {
    await load('컴공 주점')
    vi.mocked(apiFetch).mockResolvedValueOnce(
      new Response(JSON.stringify({ error: { code: 'INVALID_REQUEST', message: '지원하지 않는 필드입니다: name' } }), { status: 400 }),
    )
    edit('소웨 주점')
    await submit()
    expect(JSON.stringify(render())).toContain('지원하지 않는 필드입니다')
  })

  it('조회에 실패하면 안내를 보여준다', async () => {
    vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status: 500 }))
    render()
    await vi.waitFor(() => expect(JSON.stringify(render())).toContain('부스 정보를 불러오지 못했어요 (500)'))
  })
})
