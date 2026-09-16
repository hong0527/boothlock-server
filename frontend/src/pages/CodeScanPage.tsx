import { useNavigate } from 'react-router-dom'
import { CloseIcon } from '../components/customer/icons'

/*
 * 코드 스캔 화면 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y, node 212:704 (홈화면 - 카메라)
 * 상·하단 마스크 #090909 120px, 가운데가 카메라 프리뷰 영역.
 */

export default function CodeScanPage() {
  const navigate = useNavigate()

  return (
    <div className="relative h-screen w-full overflow-clip bg-neutral-900">
      {/* ══════════════════════════════════════════════════════════════════
          📷 카메라 프리뷰 영역 — 여기를 수정하세요 (EDIT HERE)
          Figma에서 이 영역(Rectangle 21)은 비어 있어 투명 격자로 보인다.
          실제 스캔을 붙일 때 아래 <div>를 <video>(getUserMedia)나
          QR 스캔 컴포넌트로 교체한다. 위치 클래스는 그대로 둘 것.
          ══════════════════════════════════════════════════════════════════ */}
      <div
        className="absolute inset-x-0 top-[120px] bottom-[120px] bg-[#fafafa]"
        style={{
          backgroundImage:
            'linear-gradient(45deg, #ebebeb 25%, transparent 25%, transparent 75%, #ebebeb 75%), linear-gradient(45deg, #ebebeb 25%, transparent 25%, transparent 75%, #ebebeb 75%)',
          backgroundSize: '100px 100px',
          backgroundPosition: '-13px 8px, 37px 58px',
        }}
      />
      {/* ══════════════ 카메라 프리뷰 영역 끝 ══════════════ */}

      {/* 상·하단 마스크 : #090909, 높이 120 */}
      <div className="absolute inset-x-0 top-0 h-[120px] bg-neutral-900" />
      <div className="absolute inset-x-0 bottom-0 h-[120px] bg-neutral-900" />

      {/* 타이틀 + 닫기 : top 76, 높이 24 */}
      <div className="absolute inset-x-0 top-[76px] h-6">
        <p className="text-heading-3 absolute left-1/2 -translate-x-1/2 whitespace-nowrap text-neutral-50">
          코드 스캔
        </p>
        <button
          type="button"
          aria-label="닫기"
          onClick={() => navigate('/home')}
          className="absolute right-6 top-0 h-6 w-6 text-neutral-50"
        >
          <CloseIcon className="h-6 w-6" />
        </button>
      </div>
    </div>
  )
}
