import { useMemo, useState } from 'react'
import type { TariffVersion, CalendarDay } from './api'
import { fmtDate, minToHHMM } from './tz'

const DAY_LABEL: Record<string, string> = { WORKDAY: '工作日', WEEKEND: '周末', HOLIDAY: '节假日' }

function TouBand({ v }: { v: TariffVersion }) {
  // 把墙钟分钟展开到 24h 条，含跨午夜
  const cells = Array.from({ length: 48 }, (_, i) => null as null | { type: string; label: string; price: string })
  for (const t of v.tou) {
    let m = t.startMin
    while (m !== t.endMin) {
      const idx = Math.floor(m / 30)
      cells[idx] = { type: t.periodType, label: t.periodLabel, price: t.pricePerKwh }
      m = (m + 30) % 1440
  }
  }
  return (
    <div>
      <div style={{ display: 'flex', borderRadius: 4, overflow: 'hidden', height: 26, border: '1px solid #2a3546' }}>
        {cells.map((c, i) => (
          <div key={i} title={c ? `${c.label} ${c.price}/kWh` : '未覆盖'}
            style={{ flex: 1, background: c ? periodColor(c.type) : '#3a1d1d' }} />
        ))}
      </div>
      <div className="row small muted" style={{ marginTop: 6 }}>
        {(['SHARP', 'PEAK', 'FLAT', 'VALLEY'] as const).map((t) => {
          const rule = v.tou.find((x) => x.periodType === t)
          if (!rule) return null
          return (
            <span key={t} className="badge" >
              {rule.periodLabel} {minToHHMM(rule.startMin)}–{minToHHMM(rule.endMin)}
              {rule.startMin > rule.endMin ? '（跨午夜）' : ''} ¥{rule.pricePerKwh}
            </span>
          )
        })}
      </div>
    </div>
  )
}

function periodColor(t: string): string {
  return { SHARP: '#e0564f', PEAK: '#ef9a3f', FLAT: '#58b368', VALLEY: '#4fa8e0' }[t] || '#444'
}

export function TariffCalendar({ versions, calendar, month, onMonth }: {
  versions: TariffVersion[]
  calendar: CalendarDay[]
  month: string
  onMonth: (m: string) => void
}) {
  const [expanded, setExpanded] = useState<string | null>(versions[0]?.id ?? null)

  const cells = useMemo(() => {
    const [y, m] = month.split('-').map(Number)
    const first = new Date(Date.UTC(y, m - 1, 1))
    const days = new Date(Date.UTC(y, m, 0)).getUTCDate()
    const lead = (first.getUTCDay() + 6) % 7 // 周一开头
    const byDate = new Map(calendar.map((c) => [c.localDate, c.dayType]))
    const out: ({ date: string; type: string } | null)[] = Array.from({ length: lead }, () => null)
    for (let d = 1; d <= days; d++) {
      const date = `${month}-${String(d).padStart(2, '0')}`
      out.push({ date, type: byDate.get(date) || 'WORKDAY' })
    }
    return out
  }, [calendar, month])

  return (
    <div>
      <div className="panel">
        <div className="row">
          <h2 style={{ margin: 0 }}>费率版本（生效区间）</h2>
          <span className="spacer" />
          <span className="small muted">半开区间 [生效起, 生效止)；月中调价即新版本</span>
        </div>
        {versions.map((v) => (
          <div key={v.id} className="detail-panel" style={{ marginBottom: 10 }}>
            <div className="row" style={{ cursor: 'pointer' }} onClick={() => setExpanded(expanded === v.id ? null : v.id)}>
              <strong>{v.code}</strong>
              <span className="mono small">
                {fmtDate(v.effectiveFrom)} → {v.effectiveTo ? fmtDate(v.effectiveTo) : '至今（开放）'}
              </span>
              <span className="small muted">{v.note}</span>
              <span className="spacer" />
              <span className="small muted">{expanded === v.id ? '收起 ▲' : '展开 ▼'}</span>
            </div>
            {expanded === v.id && (
              <div style={{ marginTop: 12 }}>
                <h3>尖峰平谷（固定时区墙钟）</h3>
                <TouBand v={v} />
                <h3>月度阶梯附加（在尖峰平谷能量电费之上）</h3>
                <table>
                  <thead>
                    <tr><th>档位</th><th className="num">区间 (kWh)</th><th className="num">加价 (¥/kWh)</th></tr>
                  </thead>
                  <tbody>
                    {v.tiers.map((t) => (
                      <tr key={t.tierIndex}>
                        <td>第 {t.tierIndex} 档</td>
                        <td className="num mono">
                          [{t.lowerKwh}, {t.upperKwh ?? '∞'})
                        </td>
                        <td className="num mono">+{t.surchargePerKwh}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="panel">
        <div className="row">
          <h2 style={{ margin: 0 }}>费率日历</h2>
          <input type="month" value={month} onChange={(e) => onMonth(e.target.value)} />
          <span className="spacer" />
          <span className="small muted">日历为权威来源；未标记日期按周一~周五工作日 / 周六日周末回退</span>
        </div>
        <div className="calendar-grid" style={{ marginTop: 12 }}>
          {['一', '二', '三', '四', '五', '六', '日'].map((w) => (
            <div key={w} className="small muted" style={{ textAlign: 'center' }}>{w}</div>
          ))}
          {cells.map((c, i) => (
            <div key={i} className={`cal-cell ${c?.type ?? ''}`}>
              {c && (
                <>
                  <div className="d">{Number(c.date.slice(-2))}</div>
                  <div className="muted">{DAY_LABEL[c.type]}</div>
                </>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  )
}
