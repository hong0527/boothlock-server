import { useState } from 'react'
import { requestStaffCall, type StaffCallReason } from './staffCall'

/** 손님 화면 공통 직원호출 팝업 상태 — 재확인 모달(StaffCallConfirmModal)을 여러 화면(메뉴판·장바구니·
 * 주문내역·결제안내)이 같은 방식으로 쓴다. reason은 화면마다 다르다(HELP 일반 호출, PAYMENT 결제확인). */
export function useStaffCallModal(reason: StaffCallReason) {
  const [callMessage, setCallMessage] = useState<string | null>(null)
  const [showCallConfirm, setShowCallConfirm] = useState(false)

  const handleCallStaff = async () => {
    setShowCallConfirm(false)
    const message = await requestStaffCall(reason)
    if (message) setCallMessage(message)
  }

  return {
    callMessage,
    showCallConfirm,
    openCallConfirm: () => setShowCallConfirm(true),
    closeCallConfirm: () => setShowCallConfirm(false),
    handleCallStaff,
  }
}
