export type TableOrderItem = {
  id: number
  name: string
  unitPrice: number
  qty: number
}

export type TableOrder = {
  id: number
  label: string
  startedAt: string
  items: TableOrderItem[]
  /** 편집 모드에서 자유 배치할 때 쓰는 화면상 좌표. TODO: 백엔드에 position_x/y 컬럼 생기면 서버 값으로 교체 */
  x: number
  y: number
}
