type CustomerTopBarProps = {
  boothName: string
  tableLabel: string
}

export default function CustomerTopBar({ boothName, tableLabel }: CustomerTopBarProps) {
  return (
    <div className="flex items-start justify-between bg-primary-50 px-5 pt-5 pb-6">
      <h1 className="text-heading-1 text-neutral-900">{boothName}</h1>
      <p className="text-right text-body-2 leading-[1.4] text-neutral-600">
        테이블
        <br />
        {tableLabel}번
      </p>
    </div>
  )
}