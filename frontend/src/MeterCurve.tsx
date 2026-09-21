import { useMemo, useState } from 'react'
import type { Curve } from './api'
import { fmtDateTime } from './tz'

const W = 1100
const H = 300
const PAD_L = 64
const PAD_R = 20
const PAD_T = 18
const PAD_B = 30

/** 表计曲线：累计表底数折线；缺失缺口用红色虚线连接 + 阴影带，明确“不是零”。 */
export function MeterCurve({ curve }: { curve: Curve }) {
  const [hover, setHover] = useState<number | null>(null)

  const { pts, gaps, minV, maxV, minT, maxT } = useMemo(() => {
    const pts = curve.points.map((p) => ({ t: Date.parse(p.ts), v: Number(p.readingKwh), raw: p }))
    const gaps = curve.gaps.map((g) => ({ s: Date.parse(g.start), e: Date.parse(g.end), detail: g.detail }))
    const minV = Math.min(...pts.map((p) => p.v))
    const maxV = Math.max(...pts.map((p) => p.v))
    const minT = pts.length ? pts[0].t : 0
    const maxT = pts.length ? pts[pts.length - 1].t : 1
    return { pts, gaps, minV, maxV, minT, maxT }
  }, [curve])

  if (pts.length === 0) {
    return <div className="muted small">该表计暂无读数</div>
  }

  const spanT = Math.max(1, maxT - minT)
  const spanV = Math.max(1e-9, maxV - minV)
  const x = (t: number) => PAD_L + ((t - minT) / spanT) * (W - PAD_L - PAD_R)
  const y = (v: number) => PAD_T + (1 - (v - minV) / spanV) * (H - PAD_T - PAD_B)

  const gapSet = new Set<number>()
  curve.gaps.forEach((g, i) => {
    const sIdx = pts.findIndex((p) => p.raw.ts === g.start)
    if (sIdx >= 0) gapSet.add(sIdx)
    void i
  })

  const linePath = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.v).toFixed(1)}`).join(' ')

  const yTicks = 4
  const tickVals = Array.from({ length: yTicks + 1 }, (_, i) => minV + (spanV * i) / yTicks)

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto' }}>
        {gaps.map((g, i) => (
          <rect key={i} x={x(g.s)} y={PAD_T} width={Math.max(2, x(g.e) - x(g.s))} height={H - PAD_T - PAD_B}
            fill="#7a3333" opacity={0.18} />
        ))}
        {tickVals.map((tv, i) => (
          <g key={i}>
            <line x1={PAD_L} x2={W - PAD_R} y1={y(tv)} y2={y(tv)} stroke="#2a3546" strokeDasharray="3 3" />
            <text x={PAD_L - 8} y={y(tv) + 4} textAnchor="end" fontSize="10" fill="#8b98ab">{tv.toFixed(1)}</text>
          </g>
        ))}
        {/* 正常连线（跳过缺失端点对，缺口单独画虚线） */}
        {pts.map((p, i) => {
          if (i === 0) return null
          const prev = pts[i - 1]
          const isGap = gaps.some((g) => g.s === prev.t && g.e === p.t)
          if (isGap) return null
          return <line key={i} x1={x(prev.t)} y1={y(prev.v)} x2={x(p.t)} y2={y(p.v)} stroke="#4f9dff" strokeWidth={2} />
        })}
        {gaps.map((g, i) => {
          const a = pts.find((p) => p.t === g.s)
          const b = pts.find((p) => p.t === g.e)
          if (!a || !b) return null
          return <line key={`gap-${i}`} x1={x(a.t)} y1={y(a.v)} x2={x(b.t)} y2={y(b.v)}
            stroke="#e0564f" strokeWidth={2} strokeDasharray="6 4" />
        })}
        {pts.map((p, i) => (
          <circle key={i} cx={x(p.t)} cy={y(p.v)} r={hover === i ? 5 : 3}
            fill={gapSet.has(i - 1) || gapSet.has(i) ? '#e0564f' : '#4f9dff'}
            onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
        ))}
        {pts.filter((_, i) => i % Math.ceil(pts.length / 10) === 0 || i === pts.length - 1).map((p, i) => (
          <text key={`x-${i}`} x={x(p.t)} y={H - 10} textAnchor="middle" fontSize="9" fill="#8b98ab">
            {fmtDateTime(p.raw.ts).slice(5, 16)}
          </text>
        ))}
        {hover !== null && (
          <g>
            <rect x={Math.min(W - 230, x(pts[hover].t) + 8)} y={y(pts[hover].v) - 34} width={220} height={28}
              rx={4} fill="#0b0f15" stroke="#2a3546" />
            <text x={Math.min(W - 222, x(pts[hover].t) + 14)} y={y(pts[hover].v) - 15} fontSize="11" fill="#e6ecf4">
              {fmtDateTime(pts[hover].raw.ts)} · {pts[hover].v} kWh
            </text>
          </g>
        )}
      </svg>
      <div className="small muted">
        蓝线为相邻有效读数；<span style={{ color: '#e0564f' }}>红色虚线 + 阴影</span> 为缺失读数区间
        （间隔异常，电量不计费、绝不当零）；Y 轴为累计表底数 (kWh)。
      </div>
    </div>
  )
}
