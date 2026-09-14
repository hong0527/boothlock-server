export default function SectionHeader({ title }: { title: string }) {
  return (
    <div className="flex h-[66px] items-center justify-center border-b border-neutral-100">
      <h1 className="text-[22px] leading-[1.2] font-semibold tracking-[-0.04em] text-neutral-900">{title}</h1>
    </div>
  )
}
