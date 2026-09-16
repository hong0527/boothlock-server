import { useNavigate } from 'react-router-dom'
import { BackArrowIcon } from './icons'

export default function BackButton() {
  const navigate = useNavigate()

  return (
    <button
      type="button"
      onClick={() => navigate(-1)}
      aria-label="뒤로가기"
      className="flex h-8 w-8 items-center justify-center text-neutral-900"
    >
      <BackArrowIcon className="h-8 w-8" />
    </button>
  )
}
