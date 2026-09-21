import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * 최상위 오류 경계 — 렌더 중 예외가 나면 앱이 통째로 빈 화면이 되는 것을 막는다.
 *
 * <p>이게 없으면 손님은 흰 화면만 보고 직원에게 물어볼 근거조차 없다. 운영자도 마찬가지고,
 * 실제로 저장소에 깨진 값이 남아 새로고침해도 계속 백지가 되는 경로가 있었다(safeStorage 주석 참조).
 * 되살릴 방법(새로고침·처음으로)을 화면에 남기는 것이 목적이지, 원인을 숨기려는 게 아니다.
 *
 * <p>렌더 오류만 잡는다 — 이벤트 핸들러나 비동기 콜백에서 난 예외는 React가 여기로 보내지 않으므로,
 * 그쪽은 각 화면이 try/catch로 직접 처리해야 한다.
 */
type Props = { children: ReactNode }
type State = { hasError: boolean }

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false }

  static getDerivedStateFromError(): State {
    return { hasError: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // 원인은 콘솔에만 남긴다 — 손님 화면에 스택을 띄우지 않는다
    console.error('렌더 중 오류', error, info.componentStack)
  }

  render() {
    if (!this.state.hasError) return this.props.children

    return (
      <div className="flex min-h-screen w-full flex-col items-center justify-center gap-4 bg-white px-6 text-center">
        <p className="text-heading-3 text-neutral-900">화면을 표시하지 못했어요.</p>
        <p className="text-body-1 text-neutral-400">
          잠시 후 다시 시도해주세요. 계속 같은 화면이 나오면 직원에게 말씀해주세요.
        </p>
        <div className="mt-2 flex gap-3">
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="h-[50px] rounded-xl bg-primary-300 px-6 text-body-1 font-semibold text-white"
          >
            새로고침
          </button>
          <button
            type="button"
            onClick={() => {
              window.location.href = '/'
            }}
            className="h-[50px] rounded-xl border border-neutral-300 px-6 text-body-1 font-semibold text-neutral-900"
          >
            처음으로
          </button>
        </div>
      </div>
    )
  }
}
