export function fmtTime(iso: string): string {
  // 2026-03-15T00:00:00Z → 本地园区时间（后端固定 Asia/Shanghai，浏览器按本地渲染）
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function fmtDay(iso: string | null): string {
  if (!iso) return '至今'
  return fmtTime(iso).slice(0, 10)
}

export function hhmm(min: number): string {
  if (min === 1440) return '24:00'
  return `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`
}

export const PERIOD_NAME: Record<string, string> = {
  SHARP: '尖',
  PEAK: '峰',
  FLAT: '平',
  VALLEY: '谷',
}

export const PERIOD_COLOR: Record<string, string> = {
  SHARP: 'var(--sharp)',
  PEAK: 'var(--peak)',
  FLAT: 'var(--flat)',
  VALLEY: 'var(--valley)',
}
