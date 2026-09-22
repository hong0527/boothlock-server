import { Children, isValidElement, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../lib/apiFetch'
import SettlementPage from './SettlementPage'

// DOM 의존성 추가 없이 페이지의 입력·다운로드 클릭을 실행하는 최소 hook harness (AccountPage.test.tsx와 같은 방식).
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
  onChange?: (event: { target: { value: string } }) => void
  onClick?: () => void | Promise<void>
}
function render() {
  hooks.cursor = 0
  const tree = SettlementPage()
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
async function download() {
  await find(render(), props => !!props.onClick)!.onClick!()
}

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  vi.clearAllMocks()
  // 요청 파라미터 자체만 검증하는 테스트라 매번 실패 응답(400)으로 짧게 끊는다 —
  // 200으로 응답하면 res.blob()·document.createElement 등 다운로드 DOM 경로까지 타게 되는데,
  // 이 테스트의 관심사(startAt·endAt이 변환 없이 그대로 전달되는지)와 무관하다.
  vi.mocked(apiFetch).mockResolvedValue(new Response(null, { status: 400 }))
})

describe('SettlementPage 요청 파라미터', () => {
  it('입력한 시작/마감 일시를 변환 없이 그대로 startAt·endAt 쿼리로 전달한다', async () => {
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '2026-10-01T03:00')
    await download()

    expect(apiFetch).toHaveBeenCalledTimes(1)
    const [path] = vi.mocked(apiFetch).mock.calls[0]
    const query = new URL(String(path), 'https://example.test').searchParams
    expect(query.get('startAt')).toBe('2026-09-30T22:00')
    expect(query.get('endAt')).toBe('2026-10-01T03:00')
  })

  it('9시간이 밀리는 UTC 변환(new Date·toISOString 등) 없이 KST 벽시계 문자열 그대로 전달된다', async () => {
    // new Date('2026-09-30T22:00')를 toISOString()으로 바꿨다면 KST(UTC+9) 기준 13:00으로 밀리고
    // 'Z' 접미사가 붙는다 — 이 두 가지가 없는지로 회귀를 잡는다.
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '2026-10-01T03:00')
    await download()

    const [path] = vi.mocked(apiFetch).mock.calls[0]
    const query = new URL(String(path), 'https://example.test').searchParams
    expect(query.get('startAt')).not.toContain('Z')
    expect(query.get('endAt')).not.toContain('Z')
    expect(query.get('startAt')).toContain('22:00')
    expect(query.get('endAt')).toContain('03:00')
  })

  it('시작·마감을 모두 입력하지 않으면 요청을 보내지 않는다', async () => {
    await download()
    expect(apiFetch).not.toHaveBeenCalled()
    expect(JSON.stringify(render())).toContain('모두 입력해주세요')
  })

  it('시작이 마감보다 늦으면 요청을 보내지 않는다', async () => {
    edit('시작 일시', '2026-10-01T03:00')
    edit('마감 일시', '2026-09-30T22:00')
    await download()
    expect(apiFetch).not.toHaveBeenCalled()
    expect(JSON.stringify(render())).toContain('이전이어야')
  })
})
