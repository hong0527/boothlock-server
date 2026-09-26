import { readApiError } from './apiError'
import { customerApiFetch } from './customerApiFetch'
import { getSessionToken } from './customerSession'

/** C6 직원호출 사유 — PAYMENT(v0.6.11)는 계좌이체 후 입금 확인 전용, 일반 호출과 쿨다운이 분리된다 */
export type StaffCallReason = 'HELP' | 'PAYMENT'

/** 손님 화면 공통 직원호출 호출. 성공/쿨다운/실패 메시지를 반환하고, 410으로 세션이 지워진 경우엔 빈 문자열을 반환한다
 * (이미 재스캔 화면으로 이동 중이라 문구를 덧그리지 않기 위함) */
export async function requestStaffCall(reason: StaffCallReason): Promise<string> {
  try {
    const res = await customerApiFetch('/api/v1/calls', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ reason }),
    })
    if (res.ok) return '직원을 호출했어요.'

    const { code, details } = await readApiError(res)
    if (res.status === 429 && code === 'CALL_COOLDOWN') {
      // 같은 세션·같은 사유 30초 내 재호출 제한 — 남은 시간은 details.retryAfterSeconds
      const seconds = typeof details?.retryAfterSeconds === 'number' ? details.retryAfterSeconds : null
      return seconds ? `이미 호출했어요. ${seconds}초 뒤에 다시 호출할 수 있어요.` : '이미 호출했어요. 잠시 뒤 다시 시도해주세요.'
    }
    return `직원 호출에 실패했어요 (${res.status}). 직원에게 직접 말씀해주세요.`
  } catch {
    if (!getSessionToken()) return ''
    return '서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.'
  }
}
