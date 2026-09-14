import PrimaryButton from '../components/PrimaryButton'
import TextField from '../components/TextField'

export default function LoginPage() {
  return (
    <div className="flex min-h-screen w-full items-center justify-center bg-white px-6">
      <div className="flex w-full max-w-[600px] flex-col items-center">
        {/* 로고/이미지 영역 (Figma: Rectangle 20, 236:331) */}
        <div className="h-[184px] w-full bg-neutral-200" />

        <form className="mt-16 flex w-full flex-col gap-6" onSubmit={(e) => e.preventDefault()}>
          <TextField label="아이디" labelSize="xs" placeholder="아이디 입력" />
          <TextField label="비밀번호" labelSize="xs" type="password" placeholder="비밀번호 입력" />
          <PrimaryButton type="submit" className="mt-12">
            로그인
          </PrimaryButton>
        </form>
      </div>
    </div>
  )
}
