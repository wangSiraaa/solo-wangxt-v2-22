// 园区固定时区（Asia/Shanghai 自 1992 年起无夏令时，固定 +08:00）。
export const TZ_OFFSET_MIN = 8 * 60

export function localParts(iso: string): { y: number; m: number; d: number; hh: number; mm: number; minuteOfDay: number; date: string; time: string } {
  // 后端给的是 UTC ISO（Z），转到 +08:00 墙钟
  const t = new Date(iso)
  const utc = t.getTime() + t.getTimezoneOffset() * 60000
  const local = new Date(utc + TZ_OFFSET_MIN * 60000)
  const y = local.getUTCFullYear()
  const m = local.getUTCMonth() + 1
  const d = local.getUTCDate()
  const hh = local.getUTCHours()
  const mm = local.getUTCMinutes()
  const pad = (n: number) => String(n).padStart(2, '0')
  return {
    y, m, d, hh, mm,
    minuteOfDay: hh * 60 + mm,
    date: `${y}-${pad(m)}-${pad(d)}`,
    time: `${pad(hh)}:${pad(mm)}`
  }
}

/** 本地墙钟（YYYY-MM-DDTHH:mm:ss）-> epoch ms，按固定 +08:00 解释 */
export function localToEpochMs(date: string, hh = 0, mm = 0): number {
  const pad = (n: number) => String(n).padStart(2, '0')
  return Date.parse(`${date}T${pad(hh)}:${pad(mm)}:00+08:00`)
}

export function fmtDateTime(iso: string): string {
  const p = localParts(iso)
  return `${p.date} ${p.time}`
}

export function fmtDate(iso: string): string {
  return localParts(iso).date
}

export function minToHHMM(min: number): string {
  const m = ((min % 1440) + 1440) % 1440
  return `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
}
