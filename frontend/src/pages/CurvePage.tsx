import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { Curve } from '../api/types'
import { fmtTime } from '../utils/fmt'

export function CurvePage({ meterId, month }: { meterId: number; month: string }) {
  const [curve, setCurve] = useState<Curve | null>(null)
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  useEffect(() => {
    if (!meterId) return
    setLoading(true)
    setErr('')
    api
      .curve(meterId, month)
      .then(setCurve)
      .catch((e) => setErr(e.message))
      .finally(() => setLoading(false))
  }, [meterId, month])

  if (!meterId) return <div className="muted">请先初始化样本并选择表计。</div>
  if (loading) return <div className="muted">加载曲线中…</div>
  if (err) return <div className="callout warn">无法生成曲线：{err}</div>
  if (!curve) return null

  return (
    <>
      <div className="panel">
        <h2>
          {curve.meterName}（{curve.meterCode}）· {month} 累计表底曲线
        </h2>
        <div className="legend">
          <span>
            <i style={{ background: '#4da3ff' }} />
            有效读数连线
          </span>
          <span>
            <i style={{ background: 'var(--gap)' }} />
            读数缺口（不计费，绝不当零）
          </span>
        </div>
        <CurveChart curve={curve} month={month} />
        <p className="small muted" style={{ marginTop: 8 }}>{curve.gapPolicy}</p>
      </div>

      {curve.gapPresent ? (
        <div className="callout">
          <strong>检测到 {curve.gaps.length} 个读数缺口，这些区间电量未知，不计入账单：</strong>
          <table style={{ marginTop: 8 }}>
            <thead>
              <tr>
                <th>起点</th>
                <th>终点</th>
                <th>缺口分钟</th>
                <th>判定原因</th>
              </tr>
            </thead>
            <tbody>
              {curve.gaps.map((g, i) => (
                <tr key={i}>
                  <td className="mono">{fmtTime(g.start)}</td>
                  <td className="mono">{fmtTime(g.end)}</td>
                  <td>{g.missingMinutes}</td>
                  <td className="muted">{g.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <div className="callout" style={{ borderColor: '#3a5e3a', background: '#17241a', color: '#bfe3bf' }}>
          本账月读数连续，无缺口。
        </div>
      )}
    </>
  )
}

function CurveChart({ curve, month }: { curve: Curve; month: string }) {
  const W = 1200
  const H = 320
  const PAD = 46
  const pts = curve.points
  if (pts.length < 2) {
    return <div className="muted" style={{ height: H, paddingTop: 40 }}>读数点不足，无法绘制曲线。</div>
  }
  const t0 = new Date(curve.monthStart).getTime()
  const t1 = new Date(curve.monthEnd).getTime()
  const vals = pts.map((p) => Number(p.readingKwh))
  const vMin = Math.min(...vals)
  const vMax = Math.max(...vals)
  const x = (ts: string) => PAD + ((new Date(ts).getTime() - t0) / (t1 - t0)) * (W - 2 * PAD)
  const y = (v: number) => H - PAD - (vMax === vMin ? 0.5 : (v - vMin) / (vMax - vMin)) * (H - 2 * PAD)

  // 在缺口处断线：按缺口把点序列分段
  const segments: typeof pts[] = []
  let cur: typeof pts = [pts[0]]
  for (let i = 1; i < pts.length; i++) {
    const between = curve.gaps.some(
      (g) => new Date(g.start).getTime() <= new Date(pts[i - 1].ts).getTime() &&
        new Date(g.end).getTime() >= new Date(pts[i].ts).getTime(),
    )
    if (between) {
      segments.push(cur)
      cur = [pts[i]]
    } else {
      cur.push(pts[i])
    }
  }
  segments.push(cur)

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', marginTop: 10 }}>
      {/* 网格 */}
      {[0, 0.25, 0.5, 0.75, 1].map((q) => {
        const gy = PAD + q * (H - 2 * PAD)
        const val = vMax - (vMax === vMin ? 0 : q * (vMax - vMin))
        return (
          <g key={q}>
            <line x1={PAD} y1={gy} x2={W - PAD} y2={gy} stroke="#2c3a4c" strokeWidth={1} />
            <text x={6} y={gy + 4} fill="#8ea0b5" fontSize={11}>
              {val.toFixed(0)}
            </text>
          </g>
        )
      })}
      {/* 缺口带 */}
      {curve.gaps.map((g, i) => {
        const gx = Math.max(PAD, x(g.start))
        const gx2 = Math.min(W - PAD, x(g.end))
        return (
          <rect key={i} x={gx} y={PAD} width={Math.max(1, gx2 - gx)} height={H - 2 * PAD}
                fill="#9a6bd4" opacity={0.22} />
        )
      })}
      {/* 分段折线（缺口处断开，绝不连线补零） */}
      {segments.map((seg, i) => (
        <polyline
          key={i}
          fill="none"
          stroke="#4da3ff"
          strokeWidth={1.6}
          points={seg.map((p) => `${x(p.ts)},${y(Number(p.readingKwh))}`).join(' ')}
        />
      ))}
      {pts.map((p) => (
        <circle key={p.ts} cx={x(p.ts)} cy={y(Number(p.readingKwh))} r={1.6} fill="#7fc0ff" />
      ))}
      {/* 日期轴 */}
      {Array.from({ length: 5 }, (_, i) => {
        const frac = i / 4
        const tx = PAD + frac * (W - 2 * PAD)
        const day = Math.round(frac * 30) + 1
        return (
          <text key={i} x={tx} y={H - 18} fill="#8ea0b5" fontSize={11} textAnchor="middle">
            {month}-{String(day).padStart(2, '0')}
          </text>
        )
      })}
    </svg>
  )
}
