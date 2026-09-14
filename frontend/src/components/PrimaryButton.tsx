import type { ButtonHTMLAttributes } from 'react'
import { CONTROL_BASE } from './controlStyles'

export default function PrimaryButton({ className, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...rest}
      className={`${CONTROL_BASE} bg-neutral-600 text-lg leading-[1.2] font-semibold text-neutral-50 ${className ?? ''}`}
    >
      {children}
    </button>
  )
}
