import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import jsQR from 'jsqr'
import { CloseIcon } from '../components/customer/icons'

/*
 * 코드 스캔 화면 — Figma fileKey OZSYaIZ3UgdVIAdzmq5R8y, node 212:704 (홈화면 - 카메라)
 * 상·하단 마스크 #090909 120px, 가운데가 카메라 프리뷰 영역.
 */

/** QR이 담고 있는 주소({customer.base-url}/t/{tableToken})에서 tableToken만 뽑는다 — 절대/상대 경로 둘 다 허용 */
function extractTableToken(scanned: string): string | null {
  try {
    const url = new URL(scanned)
    return url.pathname.match(/\/t\/([^/?#]+)/)?.[1] ?? null
  } catch {
    return scanned.match(/\/t\/([^/?#]+)/)?.[1] ?? null
  }
}

type ScanStatus = 'requesting' | 'scanning' | 'denied' | 'unsupported'

export default function CodeScanPage() {
  const navigate = useNavigate()
  const videoRef = useRef<HTMLVideoElement>(null)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const [status, setStatus] = useState<ScanStatus>('requesting')

  useEffect(() => {
    if (!navigator.mediaDevices?.getUserMedia) {
      setStatus('unsupported')
      return
    }

    let stream: MediaStream | null = null
    let frameId: number | null = null
    let stopped = false

    const tick = () => {
      const video = videoRef.current
      const canvas = canvasRef.current
      if (stopped || !video || !canvas || video.readyState !== video.HAVE_ENOUGH_DATA) {
        frameId = requestAnimationFrame(tick)
        return
      }

      canvas.width = video.videoWidth
      canvas.height = video.videoHeight
      const ctx = canvas.getContext('2d')
      if (!ctx) {
        frameId = requestAnimationFrame(tick)
        return
      }
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height)
      const frame = ctx.getImageData(0, 0, canvas.width, canvas.height)
      const code = jsQR(frame.data, frame.width, frame.height)

      if (code?.data) {
        const tableToken = extractTableToken(code.data)
        if (tableToken) {
          stopped = true
          navigate(`/t/${tableToken}`, { replace: true })
          return
        }
        // 부스락 QR이 아니면 무시하고 계속 스캔한다 (엉뚱한 QR을 화면에 스치기만 해도 될 수 있음)
      }
      frameId = requestAnimationFrame(tick)
    }

    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: 'environment', width: { ideal: 720 }, height: { ideal: 1280 } } })
      .then((mediaStream) => {
        if (stopped) {
          mediaStream.getTracks().forEach((track) => track.stop())
          return
        }
        stream = mediaStream
        const video = videoRef.current
        if (!video) return
        video.srcObject = mediaStream
        video.play().catch(() => {})
        setStatus('scanning')
        frameId = requestAnimationFrame(tick)
      })
      .catch(() => setStatus('denied'))

    return () => {
      stopped = true
      if (frameId !== null) cancelAnimationFrame(frameId)
      stream?.getTracks().forEach((track) => track.stop())
    }
  }, [navigate])

  return (
    <div className="relative h-screen w-full overflow-clip bg-neutral-900">
      <div className="absolute inset-x-0 top-[120px] bottom-[120px] overflow-hidden bg-[#fafafa]">
        {status === 'requesting' && (
          <div
            className="absolute inset-0"
            style={{
              backgroundImage:
                'linear-gradient(45deg, #ebebeb 25%, transparent 25%, transparent 75%, #ebebeb 75%), linear-gradient(45deg, #ebebeb 25%, transparent 25%, transparent 75%, #ebebeb 75%)',
              backgroundSize: '100px 100px',
              backgroundPosition: '-13px 8px, 37px 58px',
            }}
          />
        )}
        {(status === 'denied' || status === 'unsupported') && (
          <div className="absolute inset-0 flex items-center justify-center px-8 text-center">
            <p className="text-body-2 text-neutral-700">
              {status === 'denied'
                ? '카메라 권한이 필요해요. 브라우저 설정에서 카메라 접근을 허용해주세요.'
                : '이 브라우저에서는 카메라를 사용할 수 없어요.'}
            </p>
          </div>
        )}
        {/* eslint-disable-next-line jsx-a11y/media-has-caption */}
        <video
          ref={videoRef}
          muted
          playsInline
          className={`h-full w-full object-cover ${status === 'scanning' ? '' : 'invisible'}`}
        />
        <canvas ref={canvasRef} className="hidden" />
      </div>

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
