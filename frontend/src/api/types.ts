// 后端 API 类型（与 Spring DTO 对齐）

export interface Meter {
  id: number
  meterCode: string
  displayName: string
  tz: string
  expectedIntervalMinutes: number | null
}

export interface Gap {
  start: string
  end: string
  missingMinutes: number
  reason: string
}

export interface CurvePoint {
  ts: string
  readingKwh: string
}

export interface Curve {
  meterCode: string
  meterName: string
  monthStart: string
  monthEnd: string
  points: CurvePoint[]
  gaps: Gap[]
  gapPresent: boolean
  gapPolicy: string
}

export interface Fragment {
  start: string
  end: string
  startReading: string
  endReading: string
  fullKwh: string
  allocatedKwh: string
  allocateRatio: string
  periodType: string | null
  rateVersionId: number | null
  unitPrice: string
  tierStepNo: number | null
  clipped: boolean
}

export interface BillLine {
  kind: 'TOU' | 'TIER' | 'ROUNDING'
  label: string
  kwh: string
  unitPrice: string | null
  amountRaw: string
  amount: string
  fragments: Fragment[]
}

export interface RateVersionRef {
  id: number
  code: string
  versionNo: number
  effectiveFrom: string
  effectiveTo: string | null
}

export interface Draft {
  meterCode: string
  meterName: string
  billMonth: string
  status: string
  totalKwh: string
  totalAmount: string
  rawToBillRounding: string
  balancingRounding: string
  lines: BillLine[]
  gaps: Gap[]
  versionsUsed: RateVersionRef[]
  missingSummary: string | null
  gapPresent: boolean
}

export interface ImportReport {
  totalRows: number
  inserted: number
  updated: number
  rejected: number
  errors: string[]
  ok: boolean
}

export interface RateVersionMeta {
  id: number
  code: string
  versionNo: number
  effectiveFrom: string
  effectiveTo: string | null
  tou: boolean
  flatPrice: string | null
  note: string | null
}

export interface TouRuleRow {
  versionId: number
  type: 'SHARP' | 'PEAK' | 'FLAT' | 'VALLEY'
  dowMask: number
  startMin: number
  endMin: number
  price: string
}

export interface CalendarResponse {
  timezone: string
  versions: RateVersionMeta[]
  details: { version: RateVersionMeta; periods: TouRuleRow[] }[]
}

export interface BillSummary {
  id: number
  meterId: number
  billMonth: string
  status: 'DRAFT' | 'CONFIRMED' | 'REVERSED'
  totalKwh: string
  totalAmount: string
  roundingDiff: string
  missingInfo: string | null
  confirmedAt: string | null
  createdAt: string
}

export interface StoredFragment {
  id: number
  billLineId: number
  start: string
  end: string
  startReading: string
  endReading: string
  fullKwh: string
  allocatedKwh: string
  allocateRatio: string
  periodType: string | null
  tierStepNo: number | null
  rateVersionId: number | null
  unitPrice: string
}

export interface StoredLine {
  id: number
  billId: number
  lineKind: string
  label: string
  kwh: string
  unitPrice: string | null
  amountRaw: string
  amount: string
  sortNo: number
}

export interface BillDetail {
  bill: BillSummary & { meterCode?: string }
  meterCode: string
  meterName: string
  lines: { line: StoredLine; fragments: StoredFragment[] }[]
  gaps: Gap[]
}
