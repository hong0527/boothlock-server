import { useEffect, useRef, useState } from 'react'
import closeIcon from '../assets/icons/x.svg'
import PrimaryButton from './PrimaryButton'
import { readApiError } from '../lib/apiError'
import { getAuthToken } from '../lib/auth'
import { cartFingerprint, createIdempotencyKeyStore } from '../lib/idempotencyKey'
import { apiFetch } from '../lib/apiFetch'
import { createPollGuard } from '../lib/pollGuard'
import {
  cancelItem,
  cancelOrder,
  checkoutTable,
  confirmTablePayment,
  createManualOrder,
  updateItemQty,
} from '../lib/orderActions'
import { isUnpaid, unpaidTotal } from '../lib/sessionOrders'
import { displayTableLabel } from '../lib/tableLabel'
import { formatClockTime } from '../lib/time'
import { PAYMENT_STATUS_LABEL, type OrderStatus, type OrderSummary, type PaymentStatus } from '../types/dashboard'
import type { TableCheckoutResult, TableStatusInfo } from '../types/table'
import type { MenuItem } from '../types/menu'
import { onResume } from '../lib/onResume'

type PaymentModalProps = {
  table: TableStatusInfo
  onClose: () => void
  /** 퇴실(O6)까지 끝났을 때 — 호출부가 모달을 닫고 테이블 목록을 다시 읽는다 */
  onCheckedOut: () => void
}

const POLL_INTERVAL_MS = 5000

type FlatItem = {
  orderId: number
  itemId: number
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
  status: OrderStatus
  paymentStatus: PaymentStatus
}

// 주문내역은 "이 테이블이 지금 뭘 시켰나"를 보여주는 계산서라 같은 메뉴가 서로 다른 주문(수기 확인을 여러 번
// 눌렀거나, 손님이 추가 주문한 경우)에 나뉘어 있어도 한 줄로 합쳐 보여준다 — 주문보드는 반대로 확인 단위(주문)
// 그대로 카드를 나눠 보여줘야 해서 여기서 합치는 것과 다른 요구다. entries[0]이 가장 최근 주문의 항목이 되도록
// items(주문 최신순 배열)를 그대로 순서 보존해서 담는다 — +/- 는 그 최근 항목부터 조정한다.
type GroupedItem = {
  key: string
  menuId: number
  menuName: string
  unitPrice: number
  qty: number
  status: OrderStatus
  paymentStatus: PaymentStatus
  entries: { orderId: number; itemId: number; qty: number }[]
}

// 수기 주문 담기(제출 전) — 클릭마다 바로 createManualOrder를 부르면 치킨·콜라·감튀를 연달아 눌렀을 때
// 서로 다른 주문 3건으로 쪼개져 주문보드에 따로 뜬다. 여기 담아뒀다가 "주문 등록"에서 한 번에 보낸다.
type DraftItem = {
  menuId: number
  name: string
  price: number
  qty: number
}

// 주문내역의 +/-·취소도 새 메뉴 담기와 똑같이 "확인을 눌러야 서버에 반영되는" 임시 변경이다 — 여기 즉시
// updateItemQty·cancelItem을 부르면 X로 닫아도 이미 서버에 반영돼 되돌릴 수가 없다. 그래서 실제 항목
// (orderId·itemId)별로 원하는 최종 수량 또는 취소 여부만 로컬에 들고 있다가, 확인·비우기·결제완료 시점에
// 한 번에 커밋한다. 키는 `${orderId}:${itemId}`.
type Adjustment = { qty: number } | { canceled: true }

// 항목 수정(수량·개별 취소)이 409로 거부되는 이유를 코드별로 — 백엔드 DashboardOrderActionService 검사 순서와 같다
const ITEM_ACTION_409: Record<string, string> = {
  SOLD_OUT: '품절된 메뉴라 수량을 늘릴 수 없어요',
  ORDER_CLOSED: '주문 접수가 마감돼 수량을 늘릴 수 없어요',
  INVALID_STATE: '접수·미결제 상태의 주문만 수정할 수 있어요',
}

// 수기 주문 추가(O14, ManualOrderService)가 409로 거부되는 이유 — ManualOrderPreflight 검사 순서와 같다
const MANUAL_ADD_409: Record<string, string> = {
  SOLD_OUT: '품절된 메뉴예요.',
  ORDER_CLOSED: '지금은 주문 접수 시간이 아니에요.',
  INVALID_STATE: '테이블 상태가 바뀌었어요. 새로고침 후 다시 시도해주세요.',
}

const MENU_CATEGORY_TABS: { key: 'ALL' | 'MAIN' | 'SIDE' | 'DRINK'; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'MAIN', label: '메인 메뉴' },
  { key: 'SIDE', label: '사이드 메뉴' },
  { key: 'DRINK', label: '음료' },
]

export default function PaymentModal({ table, onClose, onCheckedOut }: PaymentModalProps) {
  const [orders, setOrders] = useState<OrderSummary[]>([])
  // 동작 실패 문구(409 등). 목록 새로고침이 성공해도 지우지 않는다 — 새로고침이 문구를 덮어 운영자가 거절 사유를 못 보던 결함(E2E F1)
  const [error, setError] = useState<string | null>(null)
  // 목록 조회 자체의 실패 문구. 동작 문구와 분리해 서로 덮어쓰지 않게 한다
  const [loadError, setLoadError] = useState<string | null>(null)
  const [checkingOut, setCheckingOut] = useState(false)
  // 요청 진행 중엔 항목 버튼을 다 막는다 — 안 막으면 연타 시 새로고침 전 값 기준으로 요청이 겹쳐서 변경분이 씹힌다
  const [busy, setBusy] = useState(false)

  // 수기 주문 추가(O14) — 메뉴 목록은 테이블과 무관하게 부스 전체라 한 번만 불러온다
  const [menus, setMenus] = useState<MenuItem[]>([])
  const [menusError, setMenusError] = useState<string | null>(null)
  const [category, setCategory] = useState<(typeof MENU_CATEGORY_TABS)[number]['key']>('ALL')
  const [draft, setDraft] = useState<DraftItem[]>([])
  const [adjustments, setAdjustments] = useState<Record<string, Adjustment>>({})

  // 반환값은 결제 확인(handleConfirmPayment)이 방금 갱신된 목록으로 미결제 합계를 다시 계산할 때 쓴다 —
  // setOrders 직후에도 이 함수를 부른 클로저의 orders/unpaidAmount는 그 렌더의 스냅샷이라 안 바뀐다
  // 폴링 응답 역전 방지 — 축제장 회선에서 먼저 보낸 요청이 늦게 도착하면 화면이 옛 목록으로 되돌아간다.
  // 다른 폴링 화면(주문현황·손님 주문내역·테이블 홈)과 같은 방식이다.
  const pollGuard = useRef(createPollGuard())

  /** skipIfBusy는 인터벌 폴링에서만 켠다 — 액션 직후의 즉시 갱신까지 건너뛰면 화면이 안 바뀐다 */
  const refetch = async ({ skipIfBusy = false }: { skipIfBusy?: boolean } = {}): Promise<OrderSummary[]> => {
    const runId = pollGuard.current.begin(skipIfBusy)
    if (runId === null) return orders
    try {
      // activeSessionOnly=true — 그 테이블의 종료 안 된 세션(지금 앉은 손님) 주문만 서버가 골라 준다.
      // businessDate를 생략하면 영업일로 거르지 않는다(v0.6.8) — 06:00을 넘긴 세션의 전날 미결제까지 보여야 O24 합계와 맞는다.
      // 세션이 없는 테이블은 빈 목록. 이전 손님의 PAID·DONE이 결제 대상·전체 취소 대상에 섞이지 않는다 (audit2 ②-2·②-3)
      const res = await apiFetch(`/api/v1/admin/orders?tableId=${table.id}&activeSessionOnly=true`)
      if (!res.ok) throw new Error(`주문 내역을 불러오지 못했어요 (${res.status})`)
      const data: { orders: OrderSummary[] } = await res.json()
      if (!pollGuard.current.isLatest(runId)) return orders
      setOrders(data.orders)
      setLoadError(null)
      return data.orders
    } catch (err) {
      if (pollGuard.current.isLatest(runId)) {
        setLoadError(err instanceof Error ? err.message : '주문 내역을 불러오지 못했어요.')
      }
      return orders
    } finally {
      pollGuard.current.end()
    }
  }

  useEffect(() => {
    refetch()
    const id = setInterval(() => refetch({ skipIfBusy: true }), POLL_INTERVAL_MS)
    const offResume = onResume(() => refetch({ skipIfBusy: true }))
    return () => {
      clearInterval(id)
      offResume()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [table.id])

  // 테이블을 바꿔 다시 열면 이전 테이블에 담다 만 임시 목록·변경사항이 새 테이블로 넘어가면 안 된다
  useEffect(() => {
    setDraft([])
    setAdjustments({})
  }, [table.id])

  useEffect(() => {
    apiFetch('/api/v1/admin/menus')
      .then((res) => {
        if (!res.ok) throw new Error(`메뉴 목록을 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: { menus: MenuItem[] }) => setMenus(data.menus))
      .catch((err) => setMenusError(err instanceof Error ? err.message : '메뉴 목록을 불러오지 못했어요.'))
  }, [])

  // 취소된 주문만 뺀다 — 완료(DONE) 주문도 보인다. 미결제 합계(O24 대상)에 DONE·UNPAID가 들어가므로 목록에도 있어야 합계와 항목이 맞는다.
  // 미결제 정의는 백엔드 UnpaidOrderRule(RECEIVED·DONE && UNPAID)과 같다 — 완료 처리만 되고 입금 안 된 주문도 미수금
  const visibleOrders = orders.filter((o) => o.status !== 'CANCELED')
  // 전체 취소는 접수(RECEIVED) 주문만 — 완료된 주문은 운영자가 주문 현황에서 개별 취소(O13)한다
  const receivedOrders = visibleOrders.filter((o) => o.status === 'RECEIVED')
  const unpaidOrders = visibleOrders.filter(isUnpaid)
  const unpaidAmount = unpaidTotal(visibleOrders)

  const itemKey = (orderId: number, itemId: number) => `${orderId}:${itemId}`

  // Figma는 "주문" 단위 그룹핑이 없다 — 주문들의 항목을 한 줄씩 평탄화해서 보여준다
  const rawItems: FlatItem[] = visibleOrders.flatMap((o) =>
    o.items.map((item) => ({ orderId: o.orderId, itemId: item.itemId, menuId: item.menuId, menuName: item.menuName,
      unitPrice: item.unitPrice, qty: item.qty, status: o.status, paymentStatus: o.paymentStatus })),
  )

  // 아직 서버에 반영 안 된 로컬 변경(adjustments)을 화면 표시용으로만 얹는다 — 실제 서버 값(rawItems)은 안 바뀐다
  const items: FlatItem[] = rawItems.flatMap((item) => {
    const adj = adjustments[itemKey(item.orderId, item.itemId)]
    if (!adj) return [item]
    if ('canceled' in adj) return []
    return [{ ...item, qty: adj.qty }]
  })

  // 같은 메뉴가 서로 다른 주문에 나뉘어 있어도(수기 확인을 여러 번 눌렀거나 손님이 추가 주문) 화면엔 한 줄
  // 합계로 보여준다. orders(→ items)는 findBySessionIdOrderByCreatedAtDescIdDesc라 최신 주문이 먼저 온다 —
  // 그래서 그룹의 entries[0]이 항상 가장 최근 주문의 항목이 된다.
  const CATEGORY_ORDER: Record<string, number> = { MAIN: 0, SIDE: 1, DRINK: 2 }
  const groupedItems: GroupedItem[] = (() => {
    const groups = new Map<string, GroupedItem>()
    for (const item of items) {
      const key = `${item.menuId}-${item.status}-${item.paymentStatus}`
      const group = groups.get(key)
      const entry = { orderId: item.orderId, itemId: item.itemId, qty: item.qty }
      if (group) {
        group.qty += item.qty
        group.entries.push(entry)
      } else {
        groups.set(key, {
          key,
          menuId: item.menuId,
          menuName: item.menuName,
          unitPrice: item.unitPrice,
          qty: item.qty,
          status: item.status,
          paymentStatus: item.paymentStatus,
          entries: [entry],
        })
      }
    }
    // 주문내역은 메인·사이드·음료 순으로 — 메뉴 목록(menus)에서 분류를 찾아 정렬한다
    return Array.from(groups.values()).sort((a, b) => {
      const rank = (menuId: number) => {
        const cat = menus.find((m) => m.id === menuId)?.category
        return cat ? CATEGORY_ORDER[cat] : 99
      }
      return rank(a.menuId) - rank(b.menuId)
    })
  })()

  const visibleMenus = menus.filter((m) => m.visible && (category === 'ALL' || m.category === category))

  // 응답을 못 받은 경우(인터넷 끊김·제한 시간 초과) — 서버에는 반영됐을 수도 있어서 목록을 새로 읽고 확인을 부탁한다.
  // 로그인 만료(apiFetch가 이미 로그인 화면으로 보내는 중)면 문구를 띄우지 않는다
  const reportNoResponse = (what: string) => {
    if (!getAuthToken()) return
    setError(`${what} — 응답을 받지 못했어요. 새로 불러온 내역에서 반영됐는지 확인하고 필요하면 다시 눌러주세요.`)
  }

  const runAction = async (
    action: () => Promise<Response>,
    failMessage: string,
    codeMap: Record<string, string> = ITEM_ACTION_409,
  ): Promise<Response | undefined> => {
    if (busy) return undefined
    setBusy(true)
    try {
      const res = await action()
      if (!res.ok) {
        const { code } = await readApiError(res)
        setError(res.status === 409 && code && codeMap[code] ? codeMap[code] : `${failMessage} (${res.status})`)
        await refetch()
        return res
      }
      setError(null)
      // 방금 등록한 주문이 결제 완료 흐름의 미결제 합계 계산에 바로 반영되도록 새로고침을 기다린 뒤 돌려준다
      await refetch()
      return res
    } catch {
      reportNoResponse(failMessage)
      await refetch()
      return undefined
    } finally {
      // action()이 던지면(토큰 만료·네트워크 오류) busy가 안 풀려서 이후 모든 버튼이 영원히 잠긴다
      setBusy(false)
    }
  }

  // O14 수기 주문 추가 — 세션이 없는(빈) 테이블이면 서버가 세션까지 만들어 OCCUPIED로 바꾼다(수기등록).
  // 새 메뉴는 즉시 주문을 만들지 않고 draft에 담아둔다 — 치킨·콜라를 연달아 눌러도 확인을 누르는 순간
  // createManualOrder 한 번으로 묶여 주문보드에 한 건으로 뜬다. 이미 등록 완료된(미결제) 주문의 항목은
  // 여기서 건드리지 않는다 — 그 항목의 수량을 늘리면 이번 확인이 이전 주문에 합쳐져 카드가 하나로
  // 뭉개진다(감사). 같은 메뉴를 다시 누르면 그때마다 새 draft·새 주문으로 분리돼야 카드가 매번 따로 뜬다.
  const addToDraft = (menuId: number, name: string, price: number) => {
    setDraft((prev) => {
      const idx = prev.findIndex((d) => d.menuId === menuId)
      if (idx >= 0) {
        return prev.map((d) => (d.menuId === menuId ? { ...d, qty: d.qty + 1 } : d))
      }
      return [...prev, { menuId, name, price, qty: 1 }]
    })
  }

  const handleAddMenu = (menu: MenuItem) => {
    if (menu.soldOut) return
    addToDraft(menu.id, menu.name, menu.price)
  }

  const adjustDraftQty = (menuId: number, delta: number) => {
    setDraft((prev) =>
      prev.map((d) => (d.menuId === menuId ? { ...d, qty: d.qty + delta } : d)).filter((d) => d.qty > 0),
    )
  }

  const removeDraftItem = (menuId: number) => {
    setDraft((prev) => prev.filter((d) => d.menuId !== menuId))
  }

  // 담아둔 메뉴를 확인/비우기/결제완료 중 아무 버튼이나 누르는 순간 한 번에 새 주문 1건으로 등록한다 —
  // 별도의 "등록" 버튼 없이, 그 버튼들이 원래 하던 동작(닫기·퇴실·결제) 앞에 얹혀서 나간다.
  // 실패하면(품절 등) false를 돌려줘 호출부가 원래 동작을 진행하지 않고 에러만 보여준 채 멈춘다.
  // 수기 주문 멱등키 — 응답을 못 받고 다시 눌러도 같은 담은 메뉴면 같은 키라 서버가 한 건만 만든다.
  // 성공하면 버린다: 같은 메뉴를 다시 담아 확인하면 새 주문이어야 한다(키 재사용 창 3분도 이중 안전장치)
  const manualKeys = useRef(createIdempotencyKeyStore())
  const submitDraftIfAny = async (): Promise<boolean> => {
    if (draft.length === 0) return true
    const items = draft.map((d) => ({ menuId: d.menuId, qty: d.qty }))
    const key = manualKeys.current.keyFor(`${table.id}|${cartFingerprint(items)}`)
    const res = await runAction(() => createManualOrder(items, table.id, key), '주문 등록에 실패했어요', MANUAL_ADD_409)
    // 400(키가 다른 주문과 겹침 등)은 같은 키로 영영 통과 못 한다 — 버려야 다음 클릭이 새 키로 나간다
    if (res?.status === 400) manualKeys.current.clear()
    if (!res?.ok) return false
    manualKeys.current.clear()
    setDraft([])
    return true
  }

  // 주문내역 줄의 "+"는 메뉴 버튼을 다시 누르는 것과 완전히 같다 — 기존 주문 수량을 늘리는 게 아니라
  // draft(담은 메뉴)에 1개 더 담는다. 그래야 "담은 메뉴"에 바로 보여서 확인 전에 뭘 더 시켰는지 한눈에
  // 보이고, 이미 등록된 주문은 건드리지 않아 카드가 하나로 뭉개지지 않는다(위 handleAddMenu와 같은 이유).
  // 품절됐으면 메뉴 버튼처럼 막는다.
  const incrementGroup = (group: GroupedItem) => {
    const menu = menus.find((m) => m.id === group.menuId)
    if (menu?.soldOut) return
    addToDraft(group.menuId, group.menuName, group.unitPrice)
  }

  // "-"는 방금 "+"로 담은아둔 draft부터 줄인다 — +2 해놓고 바로 -1 하면 아직 등록도 안 한 기존 주문을
  // 건드릴 게 아니라 담아둔 2개 중 1개를 빼는 게 맞다(안 헷갈리게). 담아둔 게 없을 때만 진짜 기존 주문
  // (adjustments)으로 넘어간다 — 서버에 바로 반영하지 않고 확인·비우기·결제완료 때 커밋, X면 사라진다.
  // 가장 최근 주문의 항목(entries[0])부터 건드린다 — 어느 주문을 조정할지 매번 고를 필요가 없다.
  // 최근 항목이 1개뿐이면 0개로 줄이는 게 아니라 그 항목 자체를 취소 대기 상태로 표시한다 —
  // updateItemQty는 qty 1 미만을 허용하지 않는다
  const decrementGroup = (group: GroupedItem) => {
    if (draft.some((d) => d.menuId === group.menuId)) {
      adjustDraftQty(group.menuId, -1)
      return
    }
    const latest = group.entries[0]
    const key = itemKey(latest.orderId, latest.itemId)
    if (latest.qty > 1) {
      setAdjustments((prev) => ({ ...prev, [key]: { qty: latest.qty - 1 } }))
    } else {
      setAdjustments((prev) => ({ ...prev, [key]: { canceled: true } }))
    }
  }

  // 이 메뉴 줄 전체를 취소한다 — 담아둔 draft가 있으면 그것도 같이 비운다. 실제 주문 항목은 그 줄이
  // 접수·미결제(editable)일 때만 취소 대기로 표시한다 — 완료·입금확인된 항목은 서버가 어차피 409로
  // 거부하므로, draft만 지우면 되는 상황에서 헛되이 에러를 띄우지 않는다.
  const cancelGroup = (group: GroupedItem) => {
    removeDraftItem(group.menuId)
    if (group.status !== 'RECEIVED' || group.paymentStatus !== 'UNPAID') return
    setAdjustments((prev) => {
      const next = { ...prev }
      for (const entry of group.entries) {
        next[itemKey(entry.orderId, entry.itemId)] = { canceled: true }
      }
      return next
    })
  }

  // 담아둔 +/-·취소를 실제로 서버에 반영한다 — draft와 마찬가지로 확인/비우기/결제완료 시점에만 불린다.
  // 실패하면(그 사이 완료·입금확인 등으로 상태가 바뀜) 나머지를 멈추고 최신 목록으로 새로고침한다.
  const applyAdjustmentsIfAny = async (): Promise<boolean> => {
    const entries = Object.entries(adjustments)
    if (entries.length === 0) return true
    if (busy) return false
    setBusy(true)
    try {
      for (const [key, adj] of entries) {
        const [orderIdStr, itemIdStr] = key.split(':')
        const res = 'canceled' in adj
          ? await cancelItem(Number(orderIdStr), Number(itemIdStr))
          : await updateItemQty(Number(orderIdStr), Number(itemIdStr), adj.qty)
        if (!res.ok) {
          const { code } = await readApiError(res)
          setError(code && ITEM_ACTION_409[code] ? ITEM_ACTION_409[code] : `수량 변경에 실패했어요 (${res.status})`)
          await refetch()
          return false
        }
        // 성공한 건 바로 걷어낸다 — 뒤에서 실패해 재시도할 때 이미 반영된 항목을 다시 건드리지 않는다
        setAdjustments((prev) => {
          const next = { ...prev }
          delete next[key]
          return next
        })
      }
      setError(null)
      await refetch()
      return true
    } catch {
      reportNoResponse('수량 변경')
      await refetch()
      return false
    } finally {
      // cancelItem·updateItemQty가 던지면(토큰 만료·네트워크 오류) busy가 안 풀려서 모달의 모든 조작이
      // 잠기고, 유일한 탈출구가 X(변경사항 버리고 닫기)뿐이던 문제 — finally로 항상 풀어준다
      setBusy(false)
    }
  }

  // 확인·비우기·결제완료 공통 진입점 — 담아둔 새 메뉴와 +/-·취소 변경을 한 번에 커밋한다
  const commitPending = async (): Promise<boolean> => {
    if (!(await applyAdjustmentsIfAny())) return false
    if (!(await submitDraftIfAny())) return false
    return true
  }

  const handleCancelAll = async () => {
    if (busy || receivedOrders.length === 0) return
    const paidCount = receivedOrders.filter((o) => o.paymentStatus === 'PAID').length
    const note = paidCount > 0 ? `\n입금확인된 ${paidCount}건은 '환불필요'로 바뀝니다.` : ''
    if (!window.confirm(`이 테이블의 접수 주문 ${receivedOrders.length}건을 모두 취소할까요?${note}`)) return
    setBusy(true)
    try {
      for (const order of receivedOrders) {
        const res = await cancelOrder(order.orderId, '테이블 전체 취소')
        if (!res.ok) {
          setError(`전체 취소 중 일부가 실패했어요 (${res.status})`)
          refetch()
          return
        }
      }
      setError(null)
      // 방금 취소된 주문의 항목을 가리키던 adjustments가 남아있으면 나중에 엉뚱한 주문에 적용될 수 있다
      setAdjustments({})
      refetch()
    } catch {
      reportNoResponse('전체 취소')
      refetch()
    } finally {
      // cancelOrder가 던지면(토큰 만료·네트워크 오류) busy가 안 풀려서 모달이 잠기는 문제 — finally로 방지
      setBusy(false)
    }
  }

  // O6 퇴실 — 성공하면 모달을 닫는다. 응답의 warning(미결제 남음)은 닫기 전에 한 번 보여준다.
  // announcedReceived = 확인창에서 "완료 처리됩니다"라고 알린 건수. 확인창 목록은 현재 영업일 주문만이라(O10 기본 조회),
  // 영업일을 넘긴 세션의 어제 접수 주문까지 서버가 완료했으면 그 차이를 함께 알린다
  /**
   * afterPayment: 방금 입금 확인(O24)이 성공한 뒤의 퇴실 — 실패 문구에 "입금은 됐다"를 밝혀야 운영자가 입금을 다시 받지 않는다.
   * 그때는 requireSettled도 켠다(그 사이 새 주문이 있으면 서버가 퇴실을 되돌림).
   */
  const checkout = async (announcedReceived = 0, { afterPayment = false } = {}): Promise<boolean> => {
    const res = await checkoutTable(table.id, { requireSettled: afterPayment })
    if (res.status === 410) {
      // 개정 전 백엔드: 이미 퇴실 처리된 테이블 — 할 일이 없으니 닫는다 (개정 후에는 멱등 200)
      onCheckedOut()
      return true
    }
    if (!res.ok) {
      const { code } = await readApiError(res)
      const paidNote = afterPayment ? '입금 확인은 완료됐어요. ' : ''
      if (res.status === 409 && code === 'CHECKOUT_UNPAID_REMAINS') {
        // 입금 확인 뒤 손님이 새로 주문했다 — 같은 일행 주문이니 그 주문까지 받고 퇴실해야 한다
        setError(`${paidNote}그 사이 새 주문이 들어와 퇴실하지 않았어요. 새 주문을 확인한 뒤 "결제 완료"를 다시 눌러주세요.`)
      } else {
        setError(`${paidNote}퇴실 처리에 실패했어요 (${res.status}). "결제 완료"를 다시 눌러 퇴실해 주세요.`)
      }
      refetch()
      return false
    }
    const result: TableCheckoutResult | null = await res.json().catch(() => null)
    const notices: string[] = []
    if (result?.warning || result?.unpaidWarning) {
      notices.push(result.warning ?? '미결제 주문이 남아 있어요. 주문 현황에서 개별 입금확인이 필요해요.')
    }
    const extraCompleted = (result?.completedOrderCount ?? 0) - announcedReceived
    if (extraCompleted > 0) {
      notices.push(`목록에 없던 다른 영업일 미완료 주문 ${extraCompleted}건도 완료 처리됐어요.`)
    }
    if (notices.length > 0) window.alert(notices.join('\n'))
    onCheckedOut()
    return true
  }

  // 퇴실(O6)하면 서버가 남은 접수 주문을 완료로 넘긴다 — 두 버튼의 확인창에 그 사실을 한 줄로 알린다
  const receivedCount = (list: OrderSummary[]) => list.filter((o) => o.status === 'RECEIVED').length
  const receivedNote = (list: OrderSummary[]) => {
    const count = receivedCount(list)
    return count > 0 ? `\n미완료 주문 ${count}건도 완료 처리됩니다.` : ''
  }

  // "결제 완료" = 미결제 합계를 확인받고 O24 일괄 입금확인 → 성공하면 O6 퇴실. 미결제 0건이면 O24 없이 바로 퇴실
  // 결제 완료·비우기 재진입 가드 — checkingOut은 확인창 뒤에야 켜져서, 그 전 refetch(최대 10초) 사이 연타하면 흐름이 둘 겹친다
  const checkoutFlowRef = useRef(false)

  const handleConfirmPayment = async () => {
    if (checkingOut || busy || checkoutFlowRef.current) return
    checkoutFlowRef.current = true
    try {
      await confirmPaymentFlow()
    } finally {
      checkoutFlowRef.current = false
    }
  }

  const confirmPaymentFlow = async () => {
    if (!(await commitPending())) return
    // commitPending이 이미 refetch를 기다렸어도, 그 안에서 만들어진 orders 클로저는 이 함수의 것과 다르다 —
    // 방금 등록·수정한 내역까지 포함한 최신 목록으로 직접 다시 계산해야 미결제 합계가 안 어긋난다
    const freshOrders = await refetch()
    const freshVisible = freshOrders.filter((o) => o.status !== 'CANCELED')
    const freshUnpaid = freshVisible.filter(isUnpaid)
    const freshUnpaidAmount = unpaidTotal(freshVisible)

    if (freshUnpaid.length === 0) {
      // 화면 목록엔 없는데 O3가 미결제를 세고 있으면(이전 영업일·목록 미반영) 그대로 남는다는 것을 알린다
      const serverNote =
        table.unpaidOrderCount > 0
          ? `\n서버 기준 미결제 ${table.unpaidOrderCount}건이 있지만 현재 목록에 없어요(다른 영업일 주문 등). 퇴실해도 그대로 남습니다.`
          : ''
      if (!window.confirm(`입금 확인할 미결제 주문이 없어요.${receivedNote(freshOrders)}\n퇴실 처리할까요?${serverNote}`)) return
      setCheckingOut(true)
      try {
        await checkout(receivedCount(freshOrders))
      } catch {
        reportNoResponse('퇴실 처리')
        refetch()
      } finally {
        setCheckingOut(false)
      }
      return
    }

    const ok = window.confirm(
      `미결제 ${freshUnpaid.length}건 · 합계 ${freshUnpaidAmount.toLocaleString()}원${receivedNote(freshOrders)}\n계좌이체 입금을 확인하고 퇴실 처리할까요?`,
    )
    if (!ok) return

    setCheckingOut(true)
    // 입금 확인(O24)과 퇴실(O6)은 요청이 둘이다 — 어디서 끊겼는지에 따라 운영자에게 할 말이 다르다
    let paymentConfirmed = false
    try {
      const res = await confirmTablePayment(table.id, freshUnpaidAmount, 'BANK_TRANSFER')
      if (!res.ok) {
        if (res.status === 409) {
          // 합계 불일치(그 사이 주문·취소)·대상 0건 — 최신 목록으로 다시 확인받는다
          setError('주문이 바뀌었어요. 새로고침된 내역을 확인한 뒤 다시 눌러주세요.')
        } else {
          const { message } = await readApiError(res)
          setError(message ? `입금 확인에 실패했어요: ${message}` : `입금 확인에 실패했어요 (${res.status})`)
        }
        refetch()
        return
      }
      paymentConfirmed = true
      // 입금은 반영됐는데 퇴실만 실패하면 checkout이 그 사실을 밝혀 알린다 — 운영자가 "처리됐다"고 믿고
      // 떠나면 다음 일행이 이 세션에 합류한다
      await checkout(receivedCount(freshOrders), { afterPayment: true })
    } catch {
      if (paymentConfirmed) {
        // 퇴실 응답만 잃었다 — 다시 누르면 미결제 0건이라 퇴실만 진행된다(O6는 멱등)
        if (getAuthToken()) {
          setError('입금 확인은 완료됐지만 퇴실 응답을 받지 못했어요. "결제 완료"를 한 번 더 눌러 퇴실해 주세요.')
        }
      } else {
        // 입금 확인이 서버에 반영됐는데 응답만 잃었을 수 있다. 다시 누르면 목록부터 새로 읽으므로
        // 이미 입금된 주문은 "미결제 없음 → 퇴실"로 넘어가 이중 처리되지 않는다(O24는 대상 0건이면 409)
        reportNoResponse('입금 확인')
      }
      refetch()
    } finally {
      // 예전에는 여기서 예외가 나면 버튼이 "처리 중..."으로 영영 잠겼다
      setCheckingOut(false)
    }
  }

  // "테이블 비우기" = 입금확인 없이 O6만(남은 접수 주문 완료는 O6가 함께 한다). 미결제가 남는다는 것을 확인받는다
  const handleVacate = async () => {
    if (checkingOut || busy || checkoutFlowRef.current) return
    checkoutFlowRef.current = true
    try {
      await vacateFlow()
    } finally {
      checkoutFlowRef.current = false
    }
  }

  const vacateFlow = async () => {
    if (!(await commitPending())) return
    const freshOrders = await refetch()
    const freshUnpaidCount = freshOrders.filter((o) => o.status !== 'CANCELED').filter(isUnpaid).length
    const unpaidCount = Math.max(freshUnpaidCount, table.unpaidOrderCount)
    const message =
      unpaidCount > 0
        ? `미결제 ${unpaidCount}건이 그대로 남습니다.${receivedNote(freshOrders)}\n입금 확인 없이 테이블을 비울까요?`
        : `테이블을 비울까요? 손님 화면은 바로 접속이 끊어져요.${receivedNote(freshOrders)}`
    if (!window.confirm(message)) return
    setCheckingOut(true)
    try {
      await checkout(receivedCount(freshOrders))
    } catch {
      reportNoResponse('테이블 비우기')
      refetch()
    } finally {
      setCheckingOut(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-6">
      {/* 높이를 max-h가 아니라 h로 고정 — 주문내역 길이에 따라 창 크기가 늘었다 줄었다 하지 않게 항상 같은 크기로 뜬다 */}
      <div className="flex h-[85vh] w-full max-w-[1100px] flex-col rounded-2xl bg-neutral-50">
        <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-5">
          <div className="flex items-baseline gap-3">
            <span className="text-[28px] leading-[1.2] font-bold tracking-[-0.04em] text-neutral-900">
              {displayTableLabel(table.label)}
            </span>
            {table.session && (
              <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-400">
                {formatClockTime(table.session.startedAt)}
              </span>
            )}
          </div>
          {/* X는 서버에 아무것도 반영하지 않고 담아둔 새 메뉴·수량 변경을 그대로 버리고 닫는다 —
              실제로 반영하려면 아래 확인·비우기·결제완료 중 하나를 눌러야 한다(commitPending) */}
          <button
            type="button"
            onClick={() => {
              setDraft([])
              setAdjustments({})
              onClose()
            }}
            aria-label="닫기"
          >
            <img src={closeIcon} alt="" className="h-9 w-9" />
          </button>
        </div>

        {(error ?? loadError) && <p className="px-6 pt-3 text-sm text-red-600">{error ?? loadError}</p>}

        <div className="flex flex-1 flex-col overflow-hidden lg:flex-row">
          {/* 왼쪽: 주문 내역 */}
          <div className="flex w-full flex-col lg:w-[380px] lg:shrink-0 lg:border-r lg:border-neutral-200">
            <div className="flex items-center justify-between border-b border-neutral-200 px-6 py-4">
              <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">주문 내역</span>
              <button
                type="button"
                onClick={handleCancelAll}
                disabled={receivedOrders.length === 0 || busy}
                className="rounded-xl border border-neutral-900 px-4 py-3 text-base leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-30"
              >
                전체 취소
              </button>
            </div>

            <div className="flex-1 overflow-y-auto">
              {groupedItems.map((group) => {
                // 서버(OrderEntity.canEditItems)는 접수+미결제만 수정을 허용한다 — 완료·입금확인 항목은 버튼을 미리 막아 409 헛클릭을 없앤다
                const editable = group.status === 'RECEIVED' && group.paymentStatus === 'UNPAID'
                // 완료·입금확인된 줄이어도 방금 "+"로 담아둔 draft가 있으면, 그 draft만큼은 되돌릴 수 있어야 한다 —
                // 안 그러면 눌러놓고 취소할 방법이 담은 메뉴 칩밖에 없어서 헷갈린다
                const hasDraftForMenu = draft.some((d) => d.menuId === group.menuId)
                const canRemove = editable || hasDraftForMenu
                return (
                  <div key={group.key} className="border-b border-neutral-200 px-6 py-5">
                    <div className="flex items-baseline justify-between">
                      <span className="flex items-baseline gap-2 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                        {group.menuName}
                        <span
                          className={`rounded-md px-1.5 py-0.5 text-xs font-medium ${
                            group.paymentStatus === 'UNPAID' ? 'bg-neutral-200 text-neutral-700' : 'bg-neutral-900 text-neutral-50'
                          }`}
                        >
                          {PAYMENT_STATUS_LABEL[group.paymentStatus]}
                        </span>
                        {group.status === 'DONE' && (
                          <span className="rounded-md bg-neutral-100 px-1.5 py-0.5 text-xs font-medium text-neutral-500">완료</span>
                        )}
                      </span>
                      <span className="text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">
                        {(group.unitPrice * group.qty).toLocaleString()}원
                      </span>
                    </div>

                    <div className="mt-2 flex items-center justify-between">
                      <span className="text-base leading-[1.5] tracking-[-0.04em] text-neutral-900">
                        {group.unitPrice.toLocaleString()}원
                      </span>
                      {/* 입금확인·완료된 주문은 서버가 수정을 409로 거부한다 — 버튼을 미리 막아 헛클릭을 없앤다 */}
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => decrementGroup(group)}
                          disabled={busy || !canRemove}
                          className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                        >
                          -
                        </button>
                        <span className="w-6 text-center text-lg font-semibold text-neutral-900">{group.qty}</span>
                        {/* +는 기존 주문을 안 건드리고 draft에 담는 것뿐이라 editable(접수·미결제) 여부와 무관하다 —
                            품절만 막는다(메뉴 버튼과 동일 기준) */}
                        <button
                          type="button"
                          onClick={() => incrementGroup(group)}
                          disabled={busy || (menus.find((m) => m.id === group.menuId)?.soldOut ?? false)}
                          className="h-8 w-8 rounded-xl border border-neutral-900 text-lg font-semibold text-neutral-900 disabled:opacity-30"
                        >
                          +
                        </button>
                        <button
                          type="button"
                          onClick={() => cancelGroup(group)}
                          disabled={busy || !canRemove}
                          className="rounded-xl border border-neutral-900 px-4 py-2 text-base font-semibold text-neutral-900 disabled:opacity-30"
                        >
                          취소
                        </button>
                      </div>
                    </div>
                  </div>
                )
              })}
              {groupedItems.length === 0 && !error && (
                <p className="px-6 py-10 text-center text-neutral-300">
                  {table.session ? '주문 내역이 없어요' : '이용 중인 손님이 없어요'}
                </p>
              )}
            </div>
          </div>

          {/* 오른쪽: 메뉴 추가(O14 수기 주문) — Figma "테이블 - 수기등록"(468:969) */}
          <div className="flex flex-1 flex-col overflow-hidden border-t border-neutral-200 lg:border-t-0">
            <div className="flex gap-6 overflow-x-auto border-b border-neutral-200 px-6 py-4">
              {MENU_CATEGORY_TABS.map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => setCategory(tab.key)}
                  className={`shrink-0 text-base leading-[1.2] font-semibold tracking-[-0.04em] ${
                    category === tab.key ? 'text-neutral-900' : 'text-neutral-400'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            {menusError && <p className="px-6 pt-3 text-sm text-red-600">{menusError}</p>}

            {draft.length > 0 && (
              <div className="flex flex-wrap items-center gap-2 border-b border-neutral-200 bg-neutral-100 px-6 py-3">
                {draft.map((d) => (
                  <span
                    key={d.menuId}
                    className="flex items-center gap-2 rounded-xl bg-white px-3 py-2 text-sm font-semibold text-neutral-900 shadow-sm"
                  >
                    {d.name} x{d.qty} · {(d.price * d.qty).toLocaleString()}원
                    <button type="button" onClick={() => adjustDraftQty(d.menuId, -1)} className="text-neutral-400" aria-label="수량 감소">
                      -
                    </button>
                    <button type="button" onClick={() => adjustDraftQty(d.menuId, 1)} className="text-neutral-400" aria-label="수량 증가">
                      +
                    </button>
                    <button type="button" onClick={() => removeDraftItem(d.menuId)} className="text-red-500" aria-label="빼기">
                      ×
                    </button>
                  </span>
                ))}
              </div>
            )}

            <div className="flex-1 overflow-y-auto p-6">
              <div className="grid grid-cols-2 gap-4 xl:grid-cols-3">
                {visibleMenus.map((menu) => (
                  <button
                    key={menu.id}
                    type="button"
                    disabled={menu.soldOut || busy}
                    onClick={() => handleAddMenu(menu)}
                    className="flex h-[120px] flex-col justify-center gap-1 rounded-2xl bg-neutral-100 px-5 text-left disabled:opacity-40"
                  >
                    <span className="text-base leading-[1.2] font-bold tracking-[-0.04em] text-neutral-900">
                      {menu.name}
                      {menu.soldOut && <span className="ml-2 text-xs font-medium text-red-500">품절</span>}
                    </span>
                    <span className="text-sm font-semibold text-neutral-400">{menu.price.toLocaleString()}원</span>
                  </button>
                ))}
                {visibleMenus.length === 0 && !menusError && (
                  <p className="col-span-full py-10 text-center text-neutral-300">이 분류에 메뉴가 없어요</p>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="flex flex-col gap-3 border-t border-neutral-200 p-6">
          <div className="flex items-baseline justify-between text-base tracking-[-0.04em] text-neutral-900">
            <span>미결제 합계 {unpaidOrders.length > 0 && <span className="text-neutral-400">({unpaidOrders.length}건)</span>}</span>
            <span className="text-lg font-semibold">{unpaidAmount.toLocaleString()}원</span>
          </div>
          <div className="flex gap-3">
            {/* 수기 주문·수량 변경만 하고 테이블은 그대로 둘 때 — 담아둔 새 메뉴와 +/-·취소를 여기서 한 번에
                커밋한 뒤 닫는다(별도의 "등록" 버튼 없이 이 버튼이 그 역할을 겸한다). 실패하면(품절·이미 완료
                처리됨 등) 에러만 보여주고 닫지 않는다. busy일 때는 막는다 — 안 막으면 요청이 끝나기 전에
                언마운트돼 실패 결과를 보여줄 화면이 이미 닫혀 조용히 묻힌다 */}
            <button
              type="button"
              onClick={async () => {
                if (await commitPending()) onClose()
              }}
              disabled={busy}
              className="h-[60px] flex-1 rounded-xl border border-neutral-900 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-40"
            >
              확인
            </button>
            <button
              type="button"
              onClick={handleVacate}
              disabled={checkingOut || busy}
              className="h-[60px] flex-1 rounded-xl border border-neutral-900 text-lg leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900 disabled:opacity-40"
            >
              테이블 비우기
            </button>
            <PrimaryButton
              type="button"
              onClick={handleConfirmPayment}
              disabled={checkingOut || busy}
              className="flex-1 disabled:opacity-40"
            >
              {checkingOut ? '처리 중...' : '결제 완료'}
            </PrimaryButton>
          </div>
        </div>
      </div>
    </div>
  )
}
