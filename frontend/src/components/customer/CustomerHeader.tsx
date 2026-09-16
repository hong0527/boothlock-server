type CustomerHeaderProps = {
  boothName?: string
  tableNumber?: string
}

export default function CustomerHeader({ boothName = '부스명', tableNumber = '1번' }: CustomerHeaderProps) {
  return (
    <header className="relative h-[119px] w-full shrink-0 bg-primary-50">
      <div className="absolute inset-x-0 bottom-0 h-[5px] bg-neutral-100" />
      <div className="absolute left-[17px] top-[51px] flex size-[55px] items-center justify-center overflow-hidden rounded-full bg-neutral-200 text-center text-[12px] leading-[1.2] text-neutral-900">
        대표사진
      </div>
      <h1 className="absolute left-[90px] top-[64px] m-0 text-[24px] leading-[1.2] font-bold tracking-[-0.96px] text-black">
        {boothName}
      </h1>
      <div className="absolute right-[17px] top-[62px] text-center text-black">
        <p className="m-0 text-[12px] leading-[1.2] font-medium tracking-[-0.48px]">테이블</p>
        <p className="m-0 text-[14px] leading-[1.2] font-medium tracking-[-0.48px]">{tableNumber}</p>
      </div>
    </header>
  )
}
