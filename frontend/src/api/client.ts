import type {
  BillDetail,
  BillSummary,
  CalendarResponse,
  Curve,
  Draft,
  ImportReport,
  Meter,
} from './types'

const BASE = '/api'

async function req<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) {
    let msg = `${res.status}`
    try {
      const body = await res.json()
      msg = body.error || JSON.stringify(body)
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }
  return res.json() as Promise<T>
}

export const api = {
  meters: () => req<Meter[]>(`${BASE}/meters`),

  curve: (meterId: number, month: string) =>
    req<Curve>(`${BASE}/meters/${meterId}/curve?month=${month}`),

  calendar: () => req<CalendarResponse>(`${BASE}/rates/calendar`),

  draft: (meterId: number, month: string) =>
    req<Draft>(`${BASE}/bills/draft?meterId=${meterId}&month=${month}`),

  confirm: (meterId: number, month: string) =>
    req<{ billId: number; status: string }>(
      `${BASE}/bills/confirm?meterId=${meterId}&month=${month}`,
      { method: 'POST' },
    ),

  reverse: (id: number) =>
    req<unknown>(`${BASE}/bills/${id}/reverse`, { method: 'POST' }),

  bills: (meterId: number) =>
    req<BillSummary[]>(`${BASE}/bills?meterId=${meterId}`),

  bill: (id: number) => req<BillDetail>(`${BASE}/bills/${id}`),

  trace: (billId: number, lineId: number) =>
    req<{ line: unknown; fragments: import('./types').StoredFragment[] }>(
      `${BASE}/bills/${billId}/lines/${lineId}/trace`,
    ),

  seed: () =>
    req<{ result: string }>(`${BASE}/admin/seed`, { method: 'POST' }),

  importCsv: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return req<ImportReport>(`${BASE}/meters/import`, { method: 'POST', body: fd })
  },
}
