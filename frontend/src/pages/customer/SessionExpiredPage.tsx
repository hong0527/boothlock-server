import { isStorageUsable } from '../../lib/safeStorage'

/**
 * 여기 오는 이유가 두 가지인데 손님이 할 일이 정반대라 문구를 가른다.
 * - 세션이 실제로 끝난 경우(퇴실·410) → QR을 다시 찍으면 된다
 * - 브라우저가 사이트 데이터를 막은 경우 → 다시 찍어도 똑같이 되돌아온다. 설정을 바꿔야 한다
 *   (예전엔 둘 다 "세션이 만료됐어요"로 안내해서, 막힌 폰은 재스캔만 반복하다 주문을 못 했다)
 */
export default function SessionExpiredPage() {
  const storageBlocked = !isStorageUsable()

  return (
    <div className="flex min-h-screen w-full flex-col items-center justify-center gap-3 bg-white px-6 text-center">
      {storageBlocked ? (
        <>
          <p className="text-heading-3 text-neutral-900">브라우저 설정 때문에 주문을 시작할 수 없어요.</p>
          <p className="text-body-1 text-neutral-400">
            사이트 데이터 저장이 꺼져 있어요. 시크릿(비공개) 창이라면 일반 창에서 열고,
            브라우저 설정에서 쿠키·사이트 데이터 차단을 풀어주세요.
          </p>
          <p className="text-body-2 text-neutral-400">해결이 어려우면 직원에게 말씀해주세요.</p>
        </>
      ) : (
        <>
          <p className="text-heading-3 text-neutral-900">세션이 만료됐어요.</p>
          <p className="text-body-1 text-neutral-400">테이블의 QR코드를 다시 스캔해주세요.</p>
        </>
      )}
    </div>
  )
}
