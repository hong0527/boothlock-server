import { Children, isValidElement, type ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAlertVolume, turnAlertsOff, turnAlertsOn } from '../lib/staffAlert'
import AlertSettingsPanel from './AlertSettingsPanel'

// DOM 없이 컴포넌트를 함수로 굴리는 최소 harness (OrderStatusPage.test.tsx와 같은 방식)
const hooks = vi.hoisted(() => ({
  values: [] as unknown[], cursor: 0, mounted: false,
  effects: [] as (() => void)[],
  alertPreferred: true,
  volume: 70,
  prefListener: null as ((on: boolean) => void) | null,
}))
vi.mock('react', async () => ({
  ...await vi.importActual<typeof import('react')>('react'),
  useState: (initial: unknown) => {
    const index = hooks.cursor++
    if (!(index in hooks.values)) hooks.values[index] = typeof initial === 'function' ? (initial as () => unknown)() : initial
    return [hooks.values[index], (value: unknown) => {
      hooks.values[index] = typeof value === 'function' ? (value as (p: unknown) => unknown)(hooks.values[index]) : value
    }]
  },
  useEffect: (effect: () => void) => { if (!hooks.mounted) hooks.effects.push(effect) },
}))
vi.mock('../lib/staffAlert', () => ({
  getAlertVolume: () => hooks.volume,
  isAlertPreferred: () => hooks.alertPreferred,
  setAlertVolume: vi.fn(),
  subscribeAlertPreference: vi.fn((listener: (on: boolean) => void) => {
    hooks.prefListener = listener
    return () => {}
  }),
  turnAlertsOff: vi.fn(),
  turnAlertsOn: vi.fn(),
}))

type Props = {
  children?: ReactNode
  on?: boolean; onToggle?: () => void; label?: string
  type?: string; value?: number; onChange?: (event: { target: { value: string } }) => void
  'aria-valuetext'?: string
}

function render() {
  hooks.cursor = 0
  const tree = AlertSettingsPanel({})
  hooks.mounted = true
  hooks.effects.splice(0).forEach(effect => effect())
  return tree
}
function collect(node: ReactNode, predicate: (props: Props) => boolean, out: Props[] = []): Props[] {
  for (const child of Children.toArray(node)) {
    if (!isValidElement<Props>(child)) continue
    if (predicate(child.props)) out.push(child.props)
    collect(child.props.children, predicate, out)
  }
  return out
}
const alertSwitch = () => collect(render(), p => !!p.onToggle)[0]
const slider = () => collect(render(), p => p.type === 'range')[0]
const texts = () => JSON.stringify(render())

beforeEach(() => {
  hooks.values = []
  hooks.cursor = 0
  hooks.mounted = false
  hooks.effects = []
  hooks.alertPreferred = true
  hooks.volume = 70
  vi.clearAllMocks()
})

describe('알림 설정(펼친 내용) — 알림음 켜고 끔', () => {
  it('설명과 함께 지금 상태를 글자(ON/OFF)로 보여 준다', () => {
    expect(texts()).toContain('주문 및 직원 호출 알림을 받습니다')
    expect(alertSwitch()).toMatchObject({ on: true, label: 'ON' })
    hooks.values = []
    hooks.mounted = false
    hooks.alertPreferred = false
    expect(alertSwitch()).toMatchObject({ on: false, label: 'OFF' })
  })

  it('켜져 있을 때 누르면 끄기, 꺼져 있을 때 누르면 켜기 — 주문현황과 같은 turnAlertsOn/Off', () => {
    alertSwitch()!.onToggle!()
    expect(turnAlertsOff).toHaveBeenCalledTimes(1)
    hooks.values = []
    hooks.mounted = false
    hooks.alertPreferred = false
    alertSwitch()!.onToggle!()
    expect(turnAlertsOn).toHaveBeenCalledTimes(1)
  })

  it('주문현황(다른 화면·탭)에서 바꾼 상태를 따라간다', () => {
    render()
    hooks.prefListener!(false)
    expect(alertSwitch()).toMatchObject({ on: false, label: 'OFF' })
    hooks.prefListener!(true)
    expect(alertSwitch()).toMatchObject({ on: true, label: 'ON' })
  })
})

describe('알림 설정(펼친 내용) — 알림음 크기', () => {
  it('저장된 크기를 슬라이더와 %로 보여 준다', () => {
    hooks.volume = 45
    expect(slider()).toMatchObject({ value: 45, 'aria-valuetext': '45%' })
    expect(texts()).toContain('45%')
  })

  it('슬라이더를 움직이면 %가 바로 바뀌고 저장된다', () => {
    slider()!.onChange!({ target: { value: '30' } })
    expect(setAlertVolume).toHaveBeenCalledWith(30)
    expect(slider()).toMatchObject({ value: 30 })
    expect(texts()).toContain('30%')
  })

  it('알림을 꺼도 크기 슬라이더는 그대로 쓸 수 있다 — 끄고 켜도 값이 남는다', () => {
    hooks.alertPreferred = false
    slider()!.onChange!({ target: { value: 90 as unknown as string } })
    expect(setAlertVolume).toHaveBeenCalledWith(90)
    expect(turnAlertsOn).not.toHaveBeenCalled()
  })
})
