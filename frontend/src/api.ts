// 后端 API 类型（金额/电量均为字符串，前端禁止用 number 解析）
export interface Meter {
  id: string
  code: string
  name: string
  timezone: string
  expectedCadenceSeconds: number
}

export interface ReadingPoint {
  ts: string
  readingKwh: string
}

export interface CurveGap {
  start: string
  end: string
  reason: string
  detail: string
}

export interface Curve {
  meterCode: string
  points: ReadingPoint[]
  gaps: CurveGap[]
  firstTs: string | null
  lastTs: string | null
}

export interface Tou {
  periodType: 'SHARP' | 'PEAK' | 'FLAT' | 'VALLEY'
  periodLabel: string
  startMin: number
  endMin: number
  pricePerKwh: string
}

export interface Tier {
  tierIndex: number
  lowerKwh: string
  upperKwh: string | null
  surchargePerKwh: string
}

export interface TariffVersion {
  id: string
  code: string
  effectiveFrom: string
  effectiveTo: string | null
  note: string
  tou: Tou[]
  tiers: Tier[]
}

export interface CalendarDay {
  localDate: string
  dayType: 'WORKDAY' | 'WEEKEND' | 'HOLIDAY'
}

export interface ImportResult {
  reused: boolean
  batchId: string
  fileName: string
  sha256: string
  rowCount: number
  importedRows: number
  duplicateRows: number
  invalidRows: number
  errors: string[]
}

export interface TierAlloc {
  id: string
  fragmentId: string
  tierIndex: number
  kwh: string
  cumulativeBefore: string
  surchargePerKwh: string
}

export interface Fragment {
  id: string
  lineId: string | null
  intervalStart: string
  intervalEnd: string
  readingStart: string
  readingEnd: string
  intervalKwh: string
  segmentStart: string
  segmentEnd: string
  share: string
  kwh: string
  periodType: string
  periodLabel: string | null
  dayType: string
  tariffId: string
  tariffCode: string | null
  pricePerKwh: string
  tierAllocs: TierAlloc[]
}

export type LineKind = 'ENERGY' | 'TIER' | 'ROUNDING'

export interface Line {
  id: string
  kind: LineKind
  periodType: string | null
  periodLabel: string | null
  tierIndex: number | null
  tariffId: string | null
  tariffCode: string | null
  kwh: string
  rawAmount: string
  amount: string
  lineOrder: number
  fragmentIds: string[]
}

export interface BillGap {
  gapStart: string
  gapEnd: string
  reason: string
  detail: string
}

export interface BillDetail {
  billId: string | null
  meterCode: string | null
  meterName: string | null
  billingMonth: string
  status: 'PREVIEW' | 'CONFIRMED'
  confirmedAt: string | null
  totalAmount: string
  totalKwh: string
  rawTotal: string
  roundedLineSum: string
  roundingAmount: string
  basisHash: string
  basisSnapshot: string
  lines: Line[]
  fragments: Fragment[]
  gaps: BillGap[]
}

export interface BillSummary {
  billId: string
  meterCode: string
  billingMonth: string
  status: string
  totalAmount: string
  totalKwh: string
  roundingAmount: string
  confirmedAt: string
}

const BASE = ''

async function jsonFetch<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(BASE + url, init)
  if (!res.ok) {
    let message = `HTTP ${res.status}`
    try {
      const body = await res.json()
      message = body.message || message
    } catch {
      /* ignore */
    }
    throw new Error(message)
  }
  return res.json()
}

export const api = {
  meta: () => jsonFetch<{ timezone: string; currency: string; missingGapFactor: number }>('/api/meta'),
  meters: () => jsonFetch<Meter[]>('/api/meters'),
  curve: (code: string) => jsonFetch<Curve>(`/api/meters/${code}/curve`),
  tariffs: () => jsonFetch<TariffVersion[]>('/api/tariffs'),
  calendar: (month: string) => jsonFetch<CalendarDay[]>(`/api/calendar?month=${month}`),
  preview: (meter: string, month: string) =>
    jsonFetch<BillDetail>(`/api/billing/preview?meter=${meter}&month=${month}`),
  confirm: (meter: string, month: string) =>
    jsonFetch<BillDetail>(`/api/billing/confirm?meter=${meter}&month=${month}`, { method: 'POST' }),
  bills: (meter?: string) =>
    jsonFetch<BillSummary[]>(`/api/bills${meter ? `?meter=${meter}` : ''}`),
  trace: (id: string) => jsonFetch<BillDetail>(`/api/bills/${id}/trace`),
  importReadings: async (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return jsonFetch<ImportResult>('/api/readings/import', { method: 'POST', body: fd })
  }
}
