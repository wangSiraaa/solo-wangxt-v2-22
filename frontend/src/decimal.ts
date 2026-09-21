import Decimal from 'decimal.js'

// 与后端一致的定点口径：金额 2 位 HALF_UP；电量保持高精度字符串
Decimal.set({ precision: 40, rounding: Decimal.ROUND_HALF_UP })

export function money(s: string | null | undefined): string {
  if (s === null || s === undefined || s === '') return '—'
  return new Decimal(s).toFixed(2)
}

export function kwh(s: string | null | undefined, digits = 6): string {
  if (s === null || s === undefined || s === '') return '—'
  return new Decimal(s).toFixed(digits)
}

export function sumMoney(items: string[]): string {
  return items.reduce((acc, v) => acc.plus(new Decimal(v)), new Decimal(0)).toFixed(2)
}

export { Decimal }
