import { useEffect, useState } from 'react'
import chevronLeft from '../../assets/icons/chevron-left.svg'
import chevronRight from '../../assets/icons/chevron-right.svg'
import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TopNav from '../../components/TopNav'
import { apiFetch } from '../../lib/apiFetch'

type TableStatus = { id: number; label: string; status: 'EMPTY' | 'OCCUPIED'; needsCleanup: boolean }

export default function TableQrPage() {
  const [tables, setTables] = useState<TableStatus[]>([])
  const [index, setIndex] = useState(0)
  const [listError, setListError] = useState<string | null>(null)

  const [qrUrl, setQrUrl] = useState<string | null>(null)
  const [qrBlob, setQrBlob] = useState<Blob | null>(null)
  const [qrError, setQrError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const table = tables[index]

  // O3 좌석 현황 — 테이블 목록 (인증 안 되어있으면 apiFetch가 알아서 로그인 화면으로 보냄)
  useEffect(() => {
    apiFetch('/api/v1/admin/tables')
      .then((res) => {
        if (!res.ok) throw new Error(`테이블 목록을 불러오지 못했어요 (${res.status})`)
        return res.json()
      })
      .then((data: { tables: TableStatus[] }) => setTables(data.tables))
      .catch((err) => setListError(err.message))
  }, [])

  // O4 QR 단건 다운로드 — 선택된 테이블의 QR 이미지
  useEffect(() => {
    if (!table) return

    let cancelled = false
    let currentUrl: string | null = null
    setLoading(true)
    setQrError(null)

    apiFetch(`/api/v1/admin/tables/${table.id}/qr`)
      .then((res) => {
        if (!res.ok) throw new Error(`QR 이미지를 불러오지 못했어요 (${res.status})`)
        return res.blob()
      })
      .then((blob) => {
        if (cancelled) return
        currentUrl = URL.createObjectURL(blob)
        setQrUrl(currentUrl)
        setQrBlob(blob)
      })
      .catch((err) => {
        if (!cancelled) setQrError(err.message)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [table])

  const handleSaveImage = () => {
    if (!qrBlob || !table) return
    const url = URL.createObjectURL(qrBlob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${table.label}-qr.png`
    // DOM에 붙였다 떼기 — 문서에 없는 앵커의 클릭은 일부 브라우저/자동화 환경에서 다운로드로 안 이어질 수 있다(SettlementPage에서 실측)
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="테이블 QR 코드 생성" />

      <div className="mx-auto flex w-full max-w-[600px] flex-col items-center gap-6 px-6 py-10">
        {listError && <p className="text-neutral-400">{listError}</p>}

        {table && (
          <>
            <div className="flex items-center gap-6">
              <button
                type="button"
                onClick={() => setIndex((i) => Math.max(0, i - 1))}
                disabled={index === 0}
                className="disabled:opacity-30"
              >
                <img src={chevronLeft} alt="이전 테이블" className="h-6 w-6" />
              </button>
              <span className="text-2xl leading-[1.2] font-bold tracking-[-0.04em] text-neutral-900">
                {table.label}
              </span>
              <button
                type="button"
                onClick={() => setIndex((i) => Math.min(tables.length - 1, i + 1))}
                disabled={index === tables.length - 1}
                className="disabled:opacity-30"
              >
                <img src={chevronRight} alt="다음 테이블" className="h-6 w-6" />
              </button>
            </div>

            <div className="flex h-[277px] w-[277px] items-center justify-center border border-neutral-200 bg-neutral-50">
              {loading && <span className="text-neutral-400">불러오는 중...</span>}
              {qrError && <span className="px-4 text-center text-neutral-400">{qrError}</span>}
              {qrUrl && !loading && !qrError && (
                <img src={qrUrl} alt={`${table.label} QR 코드`} className="h-full w-full object-contain" />
              )}
            </div>

            <PrimaryButton type="button" onClick={handleSaveImage} disabled={!qrBlob} className="disabled:opacity-40">
              이미지 저장
            </PrimaryButton>
          </>
        )}
      </div>
    </div>
  )
}
