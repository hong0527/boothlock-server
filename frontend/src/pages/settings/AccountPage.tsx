import PrimaryButton from '../../components/PrimaryButton'
import SectionHeader from '../../components/SectionHeader'
import TextField from '../../components/TextField'
import TopNav from '../../components/TopNav'

export default function AccountPage() {
  return (
    <div className="min-h-screen w-full bg-[#f4f5f7]">
      <TopNav />
      <SectionHeader title="계좌 등록" />

      <form
        // TODO: API 연결 시 계좌 등록/수정 엔드포인트로 교체 (booth 파트, 현재 GET/PATCH /admin/booth만 있음)
        onSubmit={(e) => e.preventDefault()}
        className="mx-auto flex w-full max-w-[600px] flex-col gap-6 px-6 py-10"
      >
        <TextField label="은행명" placeholder="은행명 입력" />
        <TextField label="계좌번호" placeholder="'-'를 제외하고 계좌번호 입력" inputMode="numeric" />
        <PrimaryButton type="submit" className="mt-2">
          저장하기
        </PrimaryButton>
      </form>
    </div>
  )
}
