import { describe, expect, it } from 'vitest'
import { shouldShowTableEmptyState } from './tableEmptyState'

const base = { editMode: false, loaded: true, error: null, placedCount: 0 }

describe('테이블 화면 빈 안내', () => {
  it('불러왔고 오류 없고 배치된 테이블이 0개면 띄운다', () => {
    expect(shouldShowTableEmptyState(base)).toBe(true)
  })
  it('아직 불러오는 중이면 띄우지 않는다 — 35테이블 부스도 진입 때마다 깜빡이던 문제', () => {
    expect(shouldShowTableEmptyState({ ...base, loaded: false })).toBe(false)
  })
  it('불러오기 실패면 띄우지 않는다 — 안내를 따라 눌러 테이블이 중복 생성되던 문제', () => {
    expect(shouldShowTableEmptyState({ ...base, error: '테이블 목록을 불러오지 못했어요 (500)' })).toBe(false)
  })
  it('실패했다가 다음 폴링에 회복하면 다시 정상 판단한다', () => {
    expect(shouldShowTableEmptyState({ ...base, loaded: true, error: null })).toBe(true)
  })
  it('배치된 테이블이 있으면 띄우지 않는다', () => {
    expect(shouldShowTableEmptyState({ ...base, placedCount: 35 })).toBe(false)
  })
  it('편집 모드에서는 띄우지 않는다 — 위에 이미 편집 도구가 있다', () => {
    expect(shouldShowTableEmptyState({ ...base, editMode: true })).toBe(false)
  })
})
