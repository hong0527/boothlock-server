export default function SessionExpiredPage() {
  return (
    <div className="flex min-h-screen w-full flex-col items-center justify-center gap-3 bg-white px-6 text-center">
      <p className="text-heading-3 text-neutral-900">세션이 만료됐어요.</p>
      <p className="text-body-1 text-neutral-400">테이블의 QR코드를 다시 스캔해주세요.</p>
    </div>
  )
}