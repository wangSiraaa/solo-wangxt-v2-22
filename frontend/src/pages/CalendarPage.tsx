import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { CalendarResponse, TouRuleRow } from '../api/types'
import { fmtDay, hhmm, PERIOD_COLOR, PERIOD_NAME } from '../utils/fmt'

export function CalendarPage({ month }: { month: string }) {
  const [cal, setCal] = useState<CalendarResponse | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.calendar().then(setCal).catch((e) => setErr(e.message))
  }, [month])

  if (err) return <div className="callout warn">无法加载费率日历：{err}</div>
  if (!cal) return <div className="muted">加载费率日历中…</div>

  return (
    <>
      <div className="panel">
        <h2>费率版本时间轴（固定时区 {cal.timezone}）</h2>
        <p className="small muted">
          调价 = 新版本生效区间 <span className="mono">[effective_from, effective_to)</span> 接续旧版本；
          账单试算在版本边界按分钟匀速拆分电量，快照永久保留所引用的版本。
        </p>
        <VersionTimeline cal={cal} month={month} />
      </div>

      {cal.details.map((d) => (
        <div className="panel" key={d.version.id}>
          <h2>
            {d.version.code} · 版本 #{d.version.versionNo}
            <span className="badge" style={{ marginLeft: 10 }}>
              {fmtDay(d.version.effectiveFrom)} ~ {fmtDay(d.version.effectiveTo)}
            </span>
            {d.version.note && <span className="muted" style={{ marginLeft: 10, fontWeight: 400 }}>{d.version.note}</span>}
          </h2>
          {d.version.tou ? (
            <PeriodTable periods={d.periods} />
          ) : (
            <p>非 TOU 单一单价：<span className="amount">¥{d.version.flatPrice}/kWh</span></p>
          )}
        </div>
      ))}
    </>
  )
}

function VersionTimeline({ cal, month }: { cal: CalendarResponse; month: string }) {
  // 以所选账月为观察窗（含前后 16 天），画出与本月相交的版本
  const winStart = new Date(`${month}-01T00:00:00+08:00`).getTime() - 16 * 864e5
  const winEnd = new Date(`${month}-01T00:00:00+08:00`).getTime() + 47 * 864e5
  const codes = Array.from(new Set(cal.versions.map((v) => v.code)))
  const x = (t: string | null) => {
    const ts = t ? new Date(t).getTime() : winEnd
    return Math.max(0, Math.min(1, (ts - winStart) / (winEnd - winStart))) * 100
  }
  const cut = new Date(`${month}-15T00:00:00+08:00`).getTime()
  const cutPct = ((cut - winStart) / (winEnd - winStart)) * 100

  return (
    <div>
      {codes.map((code) => (
        <div key={code} style={{ marginBottom: 14 }}>
          <div className="small muted" style={{ marginBottom: 4 }}>{code}</div>
          <div style={{ position: 'relative', height: 34, background: 'var(--panel2)', borderRadius: 6, border: '1px solid var(--line)' }}>
            {cutPct >= 0 && cutPct <= 100 && (
              <div style={{ position: 'absolute', left: `${cutPct}%`, top: 0, bottom: 0, width: 1, background: 'var(--warn)' }} title="月中调价观察线" />
            )}
            {cal.versions.filter((v) => v.code === code).map((v) => {
              const left = x(v.effectiveFrom)
              const right = x(v.effectiveTo)
              return (
                <div key={v.id}
                     style={{
                       position: 'absolute', left: `${left}%`, width: `${Math.max(2, right - left)}%`,
                       top: 5, bottom: 5, borderRadius: 4, padding: '3px 8px',
                       background: v.effectiveTo ? '#3a5070' : '#2e5f46',
                       fontSize: 12, whiteSpace: 'nowrap', overflow: 'hidden',
                     }}
                     title={`${fmtDay(v.effectiveFrom)} ~ ${fmtDay(v.effectiveTo)}`}>
                  v{v.versionNo} {fmtDay(v.effectiveFrom)} 起
                </div>
              )
            })}
          </div>
        </div>
      ))}
      <div className="small muted">绿色 = 现行开放区间版本，蓝色 = 已关闭版本；黄线为所选账月 15 日。</div>
    </div>
  )
}

function PeriodTable({ periods }: { periods: TouRuleRow[] }) {
  const days = ['一', '二', '三', '四', '五', '六', '日']
  return (
    <table>
      <thead>
        <tr>
          <th>时段类型</th>
          <th>本地时间</th>
          <th>跨午夜</th>
          <th>适用星期</th>
          <th>单价(元/kWh)</th>
        </tr>
      </thead>
      <tbody>
        {periods.map((p, i) => (
          <tr key={i} data-type={p.type}>
            <td style={{ color: PERIOD_COLOR[p.type] }}>{PERIOD_NAME[p.type]}</td>
            <td className="mono">{hhmm(p.startMin)} – {hhmm(p.endMin)}</td>
            <td>{p.startMin > p.endMin ? <span className="pill">跨午夜（当日尾段 + 次日头段）</span> : '—'}</td>
            <td>{days.filter((_, di) => p.dowMask & (1 << di)).join(' ')}</td>
            <td>{p.price}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
