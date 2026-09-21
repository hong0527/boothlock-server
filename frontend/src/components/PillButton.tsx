import type { ButtonHTMLAttributes } from 'react'

export default function PillButton({ className, children, ...rest }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...rest}
      className={`rounded-2xl bg-primary-300 px-4 py-2 text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-50 disabled:opacity-40 ${className ?? ''}`}
    >
      {children}
    </button>
  )
}
