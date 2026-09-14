import type { InputHTMLAttributes } from 'react'
import { CONTROL_BASE } from './controlStyles'

type TextFieldProps = {
  label: string
  /** Figma: 로그인 화면은 Caption(12px), 회원가입 화면은 Body 3(14px) 라벨을 씀 */
  labelSize?: 'xs' | 'sm'
} & InputHTMLAttributes<HTMLInputElement>

export default function TextField({ label, labelSize = 'sm', className, ...inputProps }: TextFieldProps) {
  return (
    <label className="block">
      <span
        className={`block leading-[1.5] tracking-[-0.04em] text-neutral-400 ${
          labelSize === 'xs' ? 'text-xs' : 'text-sm'
        }`}
      >
        {label}
      </span>
      <input
        {...inputProps}
        className={`mt-2 ${CONTROL_BASE} border border-neutral-100 bg-neutral-50 px-[18px] text-base leading-[1.5] text-neutral-400 placeholder:text-neutral-400 focus:outline-none focus:ring-2 focus:ring-primary-300 ${className ?? ''}`}
      />
    </label>
  )
}
