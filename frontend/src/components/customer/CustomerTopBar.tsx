import { displayTableLabel } from '../../lib/tableLabel'

type CustomerTopBarProps = {
  boothName: string
  tableLabel: string
}

export default function CustomerTopBar({ boothName, tableLabel }: CustomerTopBarProps) {
  return (
    <div>
      <div className="flex h-[114px] items-end justify-between bg-primary-50 px-6 pb-5">
        <h1 className="text-heading-1 text-neutral-900">{boothName}</h1>
        <div className="text-right text-neutral-900">
          <p className="text-[12px] font-medium leading-normal tracking-[-0.04em]">테이블</p>
          <p className="text-[14px] font-medium leading-normal tracking-[-0.04em]">{displayTableLabel(tableLabel)}번</p>
        </div>
      </div>
      <div className="h-[5px] bg-neutral-100" />
    </div>
  )
}