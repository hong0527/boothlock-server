import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiUrl } from '../../lib/apiBase'
import { setCustomerSession } from '../../lib/customerSession'

type TableSessionResponse = {
  sessionToken: string
  booth: { name: string; isOpen: boolean }
  table: { label: string }
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

    let cancelled = false

    fetch(apiUrl('/api/v1/table-sessions'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tableToken }),
    })
      .then(async (res) => {
        if (!res.ok) {
          if (res.status === 404) throw new Error('유효하지 않은 QR입니다. 부스 직원에게 문의해주세요.')
          throw new Error('세션을 시작하지 못했어요. 잠시 후 다시 시도해주세요.')
        }
        return res.json() as Promise<TableSessionResponse>
      })
      .then((data) => {
        if (cancelled) return
        setCustomerSession(data.sessionToken, {
          boothName: data.booth.name,
          boothIsOpen: data.booth.isOpen,
          tableLabel: data.table.label,
        })
        // 명세서 §1.2: 토큰 교환 직후 tableToken을 주소창에서 제거 — replace 네비게이션으로 히스토리에도 안 남긴다
        // 세션 발급 직후엔 인원 선택(Figma 289:3672)부터 거친다
        navigate('/party-size', { replace: true })
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : '세션을 시작하지 못했어요.')
      })

    return () => {
      cancelled = true
    }
  }, [tableToken, navigate])

  return (
    <div className="flex min-h-screen w-full items-center justify-center bg-white px-6 text-center">
      <p className="text-body-1 text-neutral-400">{error ?? '테이블 확인 중...'}</p>
    </div>
  )
}