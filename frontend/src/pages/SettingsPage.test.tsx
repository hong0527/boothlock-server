import { Children, isValidElement, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AlertSettingsPanel from '../components/AlertSettingsPanel'
import SettingsPage from './SettingsPage'

// DOM 없이 페이지를 함수로 굴리는 최소 harness (OrderStatusPage.test.tsx와 같은 방식)
const hooks = vi.hoisted(() => ({ values: [] as unknown[], cursor: 0, role: 'ADMIN' as 'ADMIN' | 'STAFF' }))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    return [hooks.values[index], (value: unknown) => {
      hooks.values[index] = typeof value === 'function' ? (value as (p: unknown) => unknown)(hooks.values[index]) : value
    }]
  },
}))
vi.mock('react-router-dom', async () => ({
  ...await vi.importActual<typeof import('react-router-dom')>('react-router-dom'),
  useNavigate: () => vi.fn(),
}))
vi.mock('../lib/auth', () => ({ clearAuth: vi.fn(), getStaff: () => ({ role: hooks.role }) }))
// 펼친 내용은 따로 테스트한다(AlertSettingsPanel.test.tsx) — 여기서는 열렸는지만 본다
vi.mock('../components/AlertSettingsPanel', () => ({ default: () => null }))

type Props = { children?: ReactNode; onClick?: () => void; 'aria-expanded'?: boolean }

function render() {
  hooks.cursor = 0
  return SettingsPage()
}
function flatten(node: ReactNode, out: { type: unknown; props: Props }[] = []) {
  for (const child of Children.toArray(node)) {
    if (!isValidElement<Props>(child)) continue
    out.push({ type: child.type, props: child.props })
    flatten(child.props.children, out)
  }
  return out
}
/** 목록 줄의 글자를 위에서부터 */
function rowLabels(): string[] {
  return flatten(render())
    .filter(e => e.props.children !== undefined && (e.props.onClick || 'to' in e.props))
    .map(e => Children.toArray(e.props.children).find(k => typeof k === 'string') as string)
    .filter(Boolean)
}
const alertRow = () => flatten(render()).find(e => e.props['aria-expanded'] !== undefined)!.props
const panelShown = () => flatten(render()).some(e => e.type === AlertSettingsPanel)

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.role = 'ADMIN'
})

describe('설정 — 알림 설정 항목', () => {
  it('정산 엑셀 다운로드 바로 아래, 로그아웃 위에 있다', () => {
    const labels = rowLabels()
    expect(labels.indexOf('알림 설정')).toBe(labels.indexOf('정산 엑셀 다운로드') + 1)
    expect(labels.at(-1)).toBe('로그아웃')
  })

  it('정산 항목이 없는 STAFF도 로그아웃 바로 위에서 알림 설정을 본다', () => {
    hooks.role = 'STAFF'
    const labels = rowLabels()
    expect(labels).not.toContain('정산 엑셀 다운로드')
    expect(labels.indexOf('알림 설정')).toBe(labels.length - 2)
  })

  it('처음에는 접혀 있고, 누르면 펼치고, 다시 누르면 접는다', () => {
    expect(alertRow()['aria-expanded']).toBe(false)
    expect(panelShown()).toBe(false)
    alertRow().onClick!()
    expect(alertRow()['aria-expanded']).toBe(true)
    expect(panelShown()).toBe(true)
    alertRow().onClick!()
    expect(panelShown()).toBe(false)
  })
})
