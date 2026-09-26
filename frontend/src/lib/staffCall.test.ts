import { describe, expect, it, vi } from 'vitest'

vi.mock('./customerApiFetch', () => ({ customerApiFetch: vi.fn() }))
vi.mock('./customerSession', () => ({ getSessionToken: vi.fn() }))

describe('requestStaffCall — 손님 화면 직원호출 공통 처리', () => {
  it('성공하면 확인 메시지를 돌려준다', async () => {
    const { customerApiFetch } = await import('./customerApiFetch')
    vi.mocked(customerApiFetch).mockResolvedValue(new Response(null, { status: 200 }))
    const { requestStaffCall } = await import('./staffCall')

    expect(await requestStaffCall('HELP')).toBe('직원을 호출했어요.')
    expect(customerApiFetch).toHaveBeenCalledWith(
      '/api/v1/calls',
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ reason: 'HELP' }) }),
    )
  })

  it('429 CALL_COOLDOWN이면 남은 초를 포함한 문구를 돌려준다', async () => {
    const { customerApiFetch } = await import('./customerApiFetch')
    vi.mocked(customerApiFetch).mockResolvedValue(
      new Response(JSON.stringify({ error: { code: 'CALL_COOLDOWN', details: { retryAfterSeconds: 12 } } }), {
        status: 429,
      }),
    )
    const { requestStaffCall } = await import('./staffCall')

    expect(await requestStaffCall('PAYMENT')).toBe('이미 호출했어요. 12초 뒤에 다시 호출할 수 있어요.')
  })

  it('그 외 실패 응답이면 상태 코드를 포함한 실패 문구를 돌려준다', async () => {
    const { customerApiFetch } = await import('./customerApiFetch')
    vi.mocked(customerApiFetch).mockResolvedValue(new Response(null, { status: 500 }))
    const { requestStaffCall } = await import('./staffCall')

    expect(await requestStaffCall('HELP')).toBe('직원 호출에 실패했어요 (500). 직원에게 직접 말씀해주세요.')
  })

  it('410으로 세션이 지워진 뒤 네트워크 예외가 나면 빈 문자열을 돌려준다(재스캔 화면으로 이동 중)', async () => {
    const { customerApiFetch } = await import('./customerApiFetch')
    const { getSessionToken } = await import('./customerSession')
    vi.mocked(customerApiFetch).mockRejectedValue(new Error('세션이 만료됐어요.'))
    vi.mocked(getSessionToken).mockReturnValue(null)
    const { requestStaffCall } = await import('./staffCall')

    expect(await requestStaffCall('HELP')).toBe('')
  })

  it('세션이 남아있는 채로 네트워크 예외가 나면 네트워크 오류 문구를 돌려준다', async () => {
    const { customerApiFetch } = await import('./customerApiFetch')
    const { getSessionToken } = await import('./customerSession')
    vi.mocked(customerApiFetch).mockRejectedValue(new Error('network down'))
    vi.mocked(getSessionToken).mockReturnValue('still-here')
    const { requestStaffCall } = await import('./staffCall')

    expect(await requestStaffCall('PAYMENT')).toBe('서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.')
  })
})
