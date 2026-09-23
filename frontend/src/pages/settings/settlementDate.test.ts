import { describe, expect, it } from 'vitest'
import { defaultRange } from './SettlementPage'

/**
 * 영업일은 06:00(KST)에 바뀐다. 달력 날짜를 그대로 쓰면 자정 넘어 정산을 받을 때
 * 아직 시작도 안 한 다음 영업일을 조회해 빈 CSV를 받는다 — 축제가 밤늦게 끝나므로
 * 그 시간대가 곧 실제 사용 시간대다. 이 값은 입력칸의 초기값일 뿐, 사용자가 자유롭게 바꿀 수 있다.
 */
describe('정산 화면 기본 시간 범위', () => {
  it('축제 중(21일 22시)이면 21일 06:00 ~ 22일 06:00', () => {
    expect(defaultRange(new Date('2026-09-21T22:00:00+09:00'))).toEqual({
      startAt: '2026-09-21T06:00',
      endAt: '2026-09-22T06:00',
    })
  })

  it('자정 직후(22일 00시 30분)에도 아직 21일 영업일이다', () => {
    expect(defaultRange(new Date('2026-09-22T00:30:00+09:00'))).toEqual({
      startAt: '2026-09-21T06:00',
      endAt: '2026-09-22T06:00',
    })
  })

  it('새벽 5시 59분까지는 전날 영업일', () => {
    expect(defaultRange(new Date('2026-09-22T05:59:00+09:00'))).toEqual({
      startAt: '2026-09-21T06:00',
      endAt: '2026-09-22T06:00',
    })
  })

  it('6시가 되면 새 영업일로 넘어간다', () => {
    expect(defaultRange(new Date('2026-09-22T06:00:00+09:00'))).toEqual({
      startAt: '2026-09-22T06:00',
      endAt: '2026-09-23T06:00',
    })
  })

  it('브라우저 시계가 KST가 아니어도 같은 결과다', () => {
    // 같은 순간을 UTC로 표현 — 21일 22시 KST = 21일 13시 UTC
    expect(defaultRange(new Date('2026-09-21T13:00:00Z'))).toEqual({
      startAt: '2026-09-21T06:00',
      endAt: '2026-09-22T06:00',
    })
  })

  it('월이 바뀌는 경계도 맞는다', () => {
    expect(defaultRange(new Date('2026-10-01T03:00:00+09:00'))).toEqual({
      startAt: '2026-09-30T06:00',
      endAt: '2026-10-01T06:00',
    })
  })
})
