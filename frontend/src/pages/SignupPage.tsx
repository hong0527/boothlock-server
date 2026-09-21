import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import BackButton from '../components/customer/BackButton'
import PrimaryButton from '../components/PrimaryButton'
import TextField from '../components/TextField'
import { apiUrl } from '../lib/apiBase'
import { setAuth } from '../lib/auth'

type SignupErrorBody = {
  error: { code: string; message: string }
}

/**
 * 회원가입 (Figma node 237:344) — 부스 + ADMIN 계정을 한 번에 만들고 바로 로그인 처리한다.
 * 원래 API명세서 O1은 "회원가입 API 없음, 계정은 시더로만 생성"이 의도적 결정이었다 —
 * 신원 확인 없이 셀프 등록을 열면 남의 점포명을 사칭한 계좌로 손님 결제를 유도할 수 있다는 이유였음.
 * 이번엔 그 결정을 뒤집기로 팀이 정했지만, 새로운 신원 확인 절차는 만들지 않았다 — 그래서 이 화면·API
 * 모두 기본은 꺼짐이고 시연·심사 때만 켠다 (App.tsx의 SIGNUP_ENABLED, 백엔드는 BoothSignupService 참고).
 */
export default function SignupPage() {
  const navigate = useNavigate()
  const [boothName, setBoothName] = useState('')
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirm, setPasswordConfirm] = useState('')
  const [agreed, setAgreed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (loading) return
    setError(null)

    if (!boothName.trim() || !loginId.trim() || !password || !passwordConfirm) {
      setError('모든 항목을 입력해주세요.')
      return
    }
    if (password !== passwordConfirm) {
      setError('비밀번호가 일치하지 않아요.')
      return
    }
    if (password.length < 8) {
      setError('비밀번호는 8자 이상이어야 해요.')
      return
    }
    if (!agreed) {
      setError('개인정보 수집에 동의해주세요.')
      return
    }

    setLoading(true)
    try {
      const res = await fetch(apiUrl('/api/v1/admin/auth/signup'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ boothName, loginId, password }),
      })

      if (!res.ok) {
        const body: SignupErrorBody | null = await res.json().catch(() => null)
        if (body?.error?.code === 'INVALID_STATE') {
          setError('이미 사용 중인 아이디예요.')
        } else if (body?.error?.message) {
          setError(body.error.message)
        } else {
          setError('가입에 실패했어요. 잠시 후 다시 시도해주세요.')
        }
        return
      }

      const data = await res.json()
      setAuth(data.accessToken, data.staff)
      navigate('/orders')
    } catch {
      setError('서버에 연결할 수 없어요. 네트워크 상태를 확인해주세요.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen w-full bg-white">
      <div className="relative flex h-[66px] items-center justify-center border-b border-neutral-100">
        <div className="absolute left-4">
          <BackButton />
        </div>
        <h1 className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">회원가입</h1>
      </div>

      <form onSubmit={handleSubmit} className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10">
        <TextField label="점포명" placeholder="점포명 입력" value={boothName} onChange={(e) => setBoothName(e.target.value)} />
        <TextField label="아이디" placeholder="아이디 입력" value={loginId} onChange={(e) => setLoginId(e.target.value)} />
        <TextField
          label="비밀번호"
          type="password"
          placeholder="비밀번호 입력"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <TextField
          label="비밀번호 확인"
          type="password"
          placeholder="비밀번호 확인"
          value={passwordConfirm}
          onChange={(e) => setPasswordConfirm(e.target.value)}
        />

        <label className="block">
          <span className="block text-sm leading-[1.5] tracking-[-0.04em] text-neutral-400">개인정보 수집 동의</span>
          <button
            type="button"
            onClick={() => setAgreed((prev) => !prev)}
            className="mt-2 flex h-[54px] w-full items-center justify-between rounded-xl border border-neutral-100 bg-neutral-50 px-[18px] text-base leading-[1.5]"
          >
            <span className="text-neutral-900 underline [text-underline-position:from-font] decoration-solid">
              [필수] 개인정보 수집 동의
            </span>
            <span
              className={`flex h-5 w-5 items-center justify-center rounded-full border ${
                agreed ? 'border-primary-300 bg-primary-300 text-white' : 'border-neutral-300 text-transparent'
              }`}
            >
              ✓
            </span>
          </button>
        </label>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <PrimaryButton type="submit" disabled={loading} className="mt-2 disabled:opacity-40">
          {loading ? '가입 중...' : '가입하기'}
        </PrimaryButton>
      </form>
    </div>
  )
}
