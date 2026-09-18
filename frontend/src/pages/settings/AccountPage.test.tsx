import { Children, isValidElement, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../lib/apiFetch'
import AccountPage from './AccountPage'

// DOM 의존성 추가 없이 페이지의 초기화·입력·submit을 실행하는 최소 hook harness.
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
  placeholder?: string
  disabled?: boolean
  onChange?: (event: { target: { value: string } }) => void
  onSubmit?: (event: { preventDefault: () => void }) => Promise<void>
}
function render() {
  hooks.cursor = 0
  const tree = AccountPage()
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
function field(label: string) { return find(render(), props => props.label === label)! }
function edit(label: string, value: string) { field(label).onChange!({ target: { value } }) }
async function submit() {
  await find(render(), props => !!props.onSubmit)!.onSubmit!({ preventDefault() {} })
}
let stored: { bankAccount: string; depositorName: string | null }
let payloads: Record<string, unknown>[]
async function load(bankAccount: string) {
  stored = { bankAccount, depositorName: null }
  vi.mocked(apiFetch).mockImplementation(async (_path, options) => {
    if (options?.method === 'PATCH') {
      const payload = JSON.parse(String(options.body))
      payloads.push(payload)
      // API의 부분 수정·빈 문자열 거부 계약을 모사한다. 실제 백엔드 테스트는 별도로 실행한다.
      if ('bankAccount' in payload && !payload.bankAccount.trim()) return new Response(null, { status: 400 })
      stored = { ...stored, ...payload }
    }
    return new Response(JSON.stringify(stored), { status: 200 })
  })
  render()
  await vi.waitFor(() => expect(field('은행명').disabled).toBe(false))
}
beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  payloads = []
  vi.clearAllMocks()
})

describe('AccountPage 저장 요청', () => {
  it.each(['계좌 미입력 - 로그인 후 설정에서 등록', '계좌 미입력-로그인 후 설정에서 등록'])('미등록 계좌 %s에서 예금주명만 저장한다', async value => {
    await load(value)
    expect(field('은행명').value).toBe('')
    expect(field('계좌번호').value).toBe('')
    expect(field('은행명').placeholder).toBe('은행명 입력')
    expect(field('계좌번호').placeholder).toBe("'-'를 제외하고 계좌번호 입력")
    edit('예금주명', '테스트예금주')
    await submit()
    expect(payloads[0]).toEqual({ depositorName: '테스트예금주' })
    expect(stored).toEqual({ bankAccount: value, depositorName: '테스트예금주' })
    await submit()
    expect(payloads[1]).not.toHaveProperty('bankAccount')
  })
  it('미등록 상태에서 실제 계좌를 등록한다', async () => {
    await load('계좌 미입력 - 로그인 후 설정에서 등록')
    edit('은행명', '테스트은행')
    edit('계좌번호', '001-234')
    await submit()
    expect(stored.bankAccount).toBe('테스트은행 001234')
  })
  it('정상 계좌에서 예금주명만 저장해도 계좌를 보존한다', async () => {
    await load('테스트은행 001234')
    edit('예금주명', '테스트예금주')
    await submit()
    expect(stored).toEqual({ bankAccount: '테스트은행 001234', depositorName: '테스트예금주' })
  })
  it.each([['은행명', '새은행', '새은행 001234'], ['계좌번호', '005678', '테스트은행 005678']])('정상 계좌의 %s 변경을 저장한다', async (label, value, expected) => {
    await load('테스트은행 001234')
    edit(label, value)
    await submit()
    expect(stored.bankAccount).toBe(expected)
  })
  it.each(['테스트은행 001234', '계좌 미입력 - 로그인 후 설정에서 등록'])('계좌 편집 후 모두 지우면 빈 값을 생략하지 않는다: %s', async initial => {
    await load(initial)
    edit('은행명', '새은행')
    edit('은행명', '')
    edit('계좌번호', '')
    await submit()
    expect(payloads[0]).toHaveProperty('bankAccount', '')
    expect(stored.bankAccount).toBe(initial)
    expect(JSON.stringify(render())).toContain('저장에 실패했어요 (400)')
  })
  it('최초 등록 성공 후 다시 비워도 미등록 생략 상태로 돌아가지 않는다', async () => {
    await load('계좌 미입력 - 로그인 후 설정에서 등록')
    edit('은행명', '테스트은행')
    edit('계좌번호', '001234')
    await submit()
    edit('은행명', '')
    edit('계좌번호', '')
    await submit()
    expect(payloads[1]).toHaveProperty('bankAccount', '')
    expect(stored.bankAccount).toBe('테스트은행 001234')
  })
})
