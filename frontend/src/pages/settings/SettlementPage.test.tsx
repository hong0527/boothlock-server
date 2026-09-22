import { Children, isValidElement, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../lib/apiFetch'
import SettlementPage from './SettlementPage'

// DOM 의존성 추가 없이 페이지의 상태·입력·클릭을 실행하는 최소 hook harness (BoothNamePage.test.tsx와 같은 방식).
// useState만 모사한다 — 이 페이지는 useEffect를 쓰지 않는다. 지연 초기값(defaultRange)과 함수형 갱신
// (setRange(prev => ...))을 실제 React와 같게 처리해야 한다.
const hooks = vi.hoisted(() => ({ values: [] as unknown[], cursor: 0 }))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) {
      hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    }
    return [
      hooks.values[index],
      (value: unknown) => {
        hooks.values[index] =
          typeof value === 'function' ? (value as (prev: unknown) => unknown)(hooks.values[index]) : value
      },
    ]
  },
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
  return SettlementPage()
}
function find(node: ReactNode, predicate: (props: Props) => boolean): Props | undefined {
  for (const child of Children.toArray(node)) {
    if (!isValidElement<Props>(child)) continue
    if (predicate(child.props)) return child.props
    const nested = find(child.props.children, predicate)
    if (nested) return nested
  }
}
function field(label: string) { return find(render(), (props) => props.label === label)! }
function edit(label: string, value: string) { field(label).onChange!({ target: { value } }) }
async function download() {
  await find(render(), (props) => !!props.onClick)!.onClick!()
}

let calledPaths: string[]

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  calledPaths = []
  vi.clearAllMocks()
  // 실제 다운로드(blob→anchor)는 이 테스트의 검증 대상이 아니다 — 호출된 query parameter만 확인하고
  // 곧바로 실패시켜서 그 뒤 DOM 의존 코드(URL.createObjectURL 등, jsdom 없는 환경)까지 가지 않게 한다.
  vi.mocked(apiFetch).mockImplementation(async (path: string) => {
    calledPaths.push(path)
    throw new Error('테스트용 중단')
  })
})

describe('SettlementPage', () => {
  it('입력한 시작/마감 일시를 변환 없이 그대로 query parameter로 보낸다', async () => {
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '2026-10-01T03:00')
    await download()

    expect(calledPaths).toHaveLength(1)
    const url = new URL(calledPaths[0], 'http://localhost')
    expect(url.pathname).toBe('/api/v1/admin/reports/settlement.csv')
    // UTC 변환·9시간 이동·초 단위 보정 없이 입력값 그대로
    expect(url.searchParams.get('startAt')).toBe('2026-09-30T22:00')
    expect(url.searchParams.get('endAt')).toBe('2026-10-01T03:00')
  })

  it('자정을 넘는 범위도 그대로 전달한다', async () => {
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '2026-10-01T03:00')
    await download()

    const url = new URL(calledPaths[0], 'http://localhost')
    expect(url.searchParams.get('startAt')).toBe('2026-09-30T22:00')
    expect(url.searchParams.get('endAt')).toBe('2026-10-01T03:00')
  })

  it('시작 일시가 비어 있으면 요청을 보내지 않는다', async () => {
    edit('시작 일시', '')
    edit('마감 일시', '2026-10-01T03:00')
    await download()

    expect(calledPaths).toHaveLength(0)
    expect(JSON.stringify(render())).toContain('모두 입력해주세요')
  })

  it('마감 일시가 비어 있으면 요청을 보내지 않는다', async () => {
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '')
    await download()

    expect(calledPaths).toHaveLength(0)
    expect(JSON.stringify(render())).toContain('모두 입력해주세요')
  })

  it('시작 일시가 마감 일시보다 늦으면(역순 입력) 요청을 보내지 않는다', async () => {
    edit('시작 일시', '2026-10-01T03:00')
    edit('마감 일시', '2026-09-30T22:00')
    await download()

    expect(calledPaths).toHaveLength(0)
    expect(JSON.stringify(render())).toContain('이후여야')
  })

  it('시작과 마감이 같으면 요청을 보내지 않는다', async () => {
    edit('시작 일시', '2026-09-30T22:00')
    edit('마감 일시', '2026-09-30T22:00')
    await download()

    expect(calledPaths).toHaveLength(0)
  })
})
