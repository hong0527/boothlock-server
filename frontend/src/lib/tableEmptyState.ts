/**
 * 테이블 화면에 "테이블이 없어요" 안내를 띄울지.
 *
 * 세 가지를 반드시 가려야 한다 — 운영자가 할 행동이 다르다:
 * - 아직 불러오는 중: 목록이 빈 배열로 시작한다. 이때 안내를 띄우면 테이블이 35개인 부스도
 *   화면에 들어올 때마다 "테이블이 없어요"가 잠깐 떴다 사라진다(TableOrderProvider는 라우트마다 새로 뜬다).
 * - 불러오기 실패: 목록은 빈 배열 그대로다. 여기서 "테이블을 추가해보세요"를 띄우면, 조회만 죽고
 *   생성은 되는 장애에서 운영자가 따라 눌러 테이블이 중복 생성된다.
 * - 정말 배치된 테이블이 없음: 이때만 안내한다.
 * OrderStatusPage의 빈 목록 처리(`length === 0 && !error`)와 같은 기준이다.
 */
export function shouldShowTableEmptyState(input: {
  editMode: boolean
  loaded: boolean
  error: string | null
  placedCount: number
}): boolean {
  return !input.editMode && input.loaded && !input.error && input.placedCount === 0
}
