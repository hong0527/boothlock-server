/**
 * 백엔드 테이블 라벨(QR·주문번호에 쓰이는 값, 영숫자만 — O2 규칙)과 화면 표시 이름을 분리한다.
 * "테이블 추가"로 만든 "T-1", "T-2"... 형태만 "테이블-1", "테이블-2"로 바꿔 보여주고,
 * 그 형태가 아닌 라벨(수동 등록 등)은 원래 값을 그대로 보여준다.
 */
export function displayTableLabel(label: string): string {
  const m = /^T-(\d+)$/.exec(label)
  return m ? `테이블-${m[1]}` : label
}

/** 백엔드 TableLabelComparator와 같은 자연 정렬 기준 비교 — "A-2" < "A-10" (사전순이 아닌 숫자순) */
export function compareTableLabels(a: string, b: string): number {
  const aChunks = a.match(/\d+|\D+/g) ?? []
  const bChunks = b.match(/\d+|\D+/g) ?? []
  const len = Math.min(aChunks.length, bChunks.length)
  for (let i = 0; i < len; i++) {
    const ac = aChunks[i]
    const bc = bChunks[i]
    let cmp: number
    if (/^\d/.test(ac) && /^\d/.test(bc)) {
      cmp = Number(ac) - Number(bc) || ac.length - bc.length
    } else {
      cmp = ac < bc ? -1 : ac > bc ? 1 : 0
    }
    if (cmp !== 0) return cmp
  }
  return a.length - b.length
}
