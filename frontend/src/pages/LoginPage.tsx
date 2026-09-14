import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import PrimaryButton from '../components/PrimaryButton'
import TextField from '../components/TextField'
import { setAuth } from '../lib/auth'

type LoginErrorBody = {
  error: { code: string; message: string; details?: { retryAfterSeconds?: number } }
}

export default function LoginPage() {
  const navigate = useNavigate()
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (loading) return // 더블클릭/엔터 연타로 로그인 요청이 두 번 나가는 것 방지
    setError(null)
    setLoading(true)

    try {
      const res = await fetch('/api/v1/admin/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ loginId, password }),
      })

      if (!res.ok) {
        // 프록시/서버가 JSON이 아닌 에러 페이지를 줄 수도 있어서 파싱 자체를 방어
        const body: LoginErrorBody | null = await res.json().catch(() => null)
        const code = body?.error?.code
        if (code === 'LOGIN_LOCKED') {
          const seconds = body?.error.details?.retryAfterSeconds
          setError(seconds ? `너무 많이 틀렸어요. ${seconds}초 후 다시 시도해주세요.` : '계정이 잠겼어요. 잠시 후 다시 시도해주세요.')
        } else if (code === 'LOGIN_FAILED') {
          setError('아이디 또는 비밀번호가 올바르지 않습니다.')
        } else {
          setError('로그인에 실패했어요. 잠시 후 다시 시도해주세요.')
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
    <div className="flex min-h-screen w-full items-center justify-center bg-white px-6">
      <div className="flex w-full max-w-[600px] flex-col items-center">
        {/* 로고/이미지 영역 (Figma: Rectangle 20, 236:331) */}
        <div className="h-[184px] w-full bg-neutral-200" />

        <form className="mt-16 flex w-full flex-col gap-6" onSubmit={handleSubmit}>
          <TextField
            label="아이디"
            labelSize="xs"
            placeholder="아이디 입력"
            value={loginId}
            onChange={(e) => setLoginId(e.target.value)}
          />
          <TextField
            label="비밀번호"
            labelSize="xs"
            type="password"
            placeholder="비밀번호 입력"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {error && <p className="text-sm text-red-600">{error}</p>}
          <PrimaryButton type="submit" disabled={loading} className="mt-12 disabled:opacity-40">
            {loading ? '로그인 중...' : '로그인'}
          </PrimaryButton>
        </form>
      </div>
    </div>
  )
}
