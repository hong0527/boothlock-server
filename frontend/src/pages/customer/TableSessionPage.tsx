import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiUrl } from '../../lib/apiBase'
import { fetchWithTimeout } from '../../lib/fetchWithTimeout'
import { setCustomerSession } from '../../lib/customerSession'

type TableSessionResponse = {
  sessionToken: string
  booth: { name: string; isOpen: boolean }
  table: { label: string }
  restored: boolean
  /** 세션에 저장된 인원수(자릿세 파일럿) — 없으면 null */
  partySize: number | null
}

export default function TableSessionPage() {
  const { tableToken } = useParams()
  const navigate = useNavigate()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!tableToken) {
      setError('유효하지 않은 QR입니다. 부스 직원에게 문의해주세요.')
      return
    }

    // StrictMode(개발 모드)는 effect를 마운트→클린업→마운트로 두 번 실행한다 — 예전엔 이 요청의 결과가 뭐든
    // 항상 같은 화면(/order)으로 갔으니 무해했지만, 이제는 restored 값에 따라 목적지가 갈린다. AbortController
    // 없이 "cancelled" 불리언만으로는 실제 요청을 막지 못해 첫 실행이 세션을 만들어놓고, 두 번째 실행이 그
    // 세션을 "복원"으로 잘못 보고 인원 선택을 건너뛴다(실사용에선 안 드러남 — StrictMode 이중 마운트는 개발
    // 전용이라 배포본엔 없다. 그래도 여기서 제대로 취소해 테스트에서도 재현되지 않게 한다)
    const controller = new AbortController()

    fetchWithTimeout(
      apiUrl('/api/v1/table-sessions'),
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ tableToken }),
        signal: controller.signal,
      },
    )
      .then(async (res) => {
        if (!res.ok) {
          if (res.status === 404) throw new Error('유효하지 않은 QR입니다. 부스 직원에게 문의해주세요.')
          throw new Error('세션을 시작하지 못했어요. 잠시 후 다시 시도해주세요.')
        }
        return res.json() as Promise<TableSessionResponse>
      })
      .then((data) => {
        setCustomerSession(data.sessionToken, {
          boothName: data.booth.name,
          boothIsOpen: data.booth.isOpen,
          tableLabel: data.table.label,
          ...(data.partySize ? { partySize: data.partySize } : {}),
        })
        // 명세서 §1.2: 토큰 교환 직후 tableToken을 주소창에서 제거 — replace 네비게이션으로 히스토리에도 안 남긴다
        // 자릿세(1인당 3,000원, 첫 주문에만 부과)가 인원수를 필요로 해서 다시 인원 선택을 거친다(2026-09-23 피드백,
        // PR #62가 "결제 금액에 영향 없다"며 건너뛰게 했던 걸 되돌림) — 단, 이미 앉아있던 손님이 QR을 다시 찍은
        // 경우는 인원수를 또 물어보지 않고 바로 메뉴로 간다. 판단은 restored가 아니라 "세션에 인원수가 있나"로 한다 —
        // 두 번째 폰·응답 유실 뒤 재스캔은 restored인데 아직 아무도 인원을 고르지 않았을 수 있고, 그대로 주문하면 자릿세가 0원이 됐다
        navigate(data.partySize ? '/order' : '/party-size', { replace: true })
      })
      .catch((err) => {
        if (controller.signal.aborted) return // 클린업으로 취소된 요청 — 화면을 이미 떠났거나 StrictMode 재실행
        setError(err instanceof Error ? err.message : '세션을 시작하지 못했어요.')
      })

    return () => {
      controller.abort()
    }
  }, [tableToken, navigate])

  return (
    <div className="flex min-h-screen w-full items-center justify-center bg-white px-6 text-center">
      <p className="text-body-1 text-neutral-400">{error ?? '테이블 확인 중...'}</p>
    </div>
  )
}