import { useMemo, useState } from 'react'
import type { BillDetail, Line, Fragment } from './api'
import { Decimal, money, kwh } from './decimal'
import { fmtDateTime } from './tz'

const PERIOD_NAME: Record<string, string> = { SHARP: '尖', PEAK: '峰', FLAT: '平', VALLEY: '谷' }
const GAP_NAME: Record<string, string> = {
  MISSING_READING: '缺失读数', BEFORE_FIRST: '首条读数之前', AFTER_LAST: '末条读数之后'
}

function lineName(l: Line): string {
  if (l.kind === 'ROUNDING') return '舍入差额（总分舍入 − 分项舍入之和）'
  if (l.kind === 'TIER') return `第 ${l.tierIndex} 档阶梯附加`
  return `${PERIOD_NAME[l.periodType || ''] || ''}时段能量电费`
}

export function BillTrace({ detail, onSelectLine, selectedLineId }: {
  detail: BillDetail
  onSelectLine: (id: string) => void
  selectedLineId: string | null
}) {
  const selected = detail.lines.find((l) => l.id === selectedLineId) || null
  const fragById = useMemo(() => new Map(detail.fragments.map((f) => [f.id, f])), [detail.fragments])

  return (
    <div>
      <div className="total-bar">
        <div className="stat">
          <div className="label">应收金额（元）</div>
          <div className="value">¥{money(detail.totalAmount)}</div>
          <div className="raw">未舍入合计 {money(detail.rawTotal)}</div>
        </div>
        <div className="stat">
          <div className="label">分项舍入后合计（元）</div>
          <div className="value">¥{money(detail.roundedLineSum)}</div>
          <div className="raw">各费用项分别 HALF_UP 到分</div>
        </div>
        <div className="stat">
          <div className="label">舍入差额（元）</div>
          <div className="value" style={{ color: detail.roundingAmount.startsWith('-') ? '#e8c15a' : undefined }}>
            {money(detail.roundingAmount)}
          </div>
          <div className="raw">独立费用项，保证恒等：分项合计+差额=应收</div>
        </div>
        <div className="stat">
          <div className="label">计费电量（kWh）</div>
          <div className="value">{kwh(detail.totalKwh, 3)}</div>
          <div className="raw">缺失区间电量不计入</div>
        </div>
        <div className="stat">
          <div className="label">状态 / 账期</div>
          <div className="value" style={{ fontSize: 15 }}>
            <span className={`badge ${detail.status}`}>{detail.status === 'CONFIRMED' ? '已确认（依据冻结）' : '试算'}</span>
          </div>
          <div className="raw mono">{detail.billingMonth} · {detail.confirmedAt ? fmtDateTime(detail.confirmedAt) : '未保存'}</div>
        </div>
      </div>

      {detail.gaps.map((g, i) => (
        <div key={i} className="gap-banner">
          ⚠ {GAP_NAME[g.reason] || g.reason}：{fmtDateTime(g.gapStart)} → {fmtDateTime(g.gapEnd)}
          <div className="small" style={{ marginTop: 2 }}>{g.detail}</div>
        </div>
      ))}

      <div className="panel">
        <h2>账单明细 — 点击任一费用项向下追溯</h2>
        <table>
          <thead>
            <tr>
              <th>#</th><th>费用项</th><th>费率版本</th><th>类型</th>
              <th className="num">电量 kWh</th><th className="num">未舍入金额</th><th className="num">金额（元）</th>
            </tr>
          </thead>
          <tbody>
            {detail.lines.map((l) => (
              <tr key={l.id} className={`clickable ${selectedLineId === l.id ? 'selected' : ''}`}
                onClick={() => onSelectLine(l.id)}>
                <td className="muted">{l.lineOrder}</td>
                <td>
                  <span className={`badge ${l.kind}`} style={{ marginRight: 8 }}>
                    {l.kind === 'ENERGY' ? l.periodLabel : l.kind === 'TIER' ? `第${l.tierIndex}档` : '舍入'}
                  </span>
                  {lineName(l)}
                </td>
                <td className="mono small">{l.tariffCode ?? '—'}</td>
                <td className="muted small">{l.kind}</td>
                <td className="num">{l.kind === 'ROUNDING' ? '—' : kwh(l.kwh, 6)}</td>
                <td className="num mono">{l.kind === 'ROUNDING' ? money(l.rawAmount) : money(l.rawAmount)}</td>
                <td className="num mono">{money(l.amount)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selected && selected.kind === 'ENERGY' && (
        <EnergyDrilldown line={selected} fragById={fragById} detail={detail} />
      )}
      {selected && selected.kind === 'TIER' && (
        <TierDrilldown line={selected} detail={detail} />
      )}
      {selected && selected.kind === 'ROUNDING' && (
        <RoundingDrilldown detail={detail} />
      )}

      <BasisPanel detail={detail} />
    </div>
  )
}

function EnergyDrilldown({ line, fragById, detail }: { line: Line; fragById: Map<string, Fragment>; detail: BillDetail }) {
  const frags = line.fragmentIds.map((id) => fragById.get(id)!).filter(Boolean)
  return (
    <div className="panel">
      <h2>参与「{lineName(line)}」计算的电量片段（{frags.length} 段）</h2>
      <p className="small muted">
        每条相邻读数区间按账期/费率版本/本地午夜/时段边界切分；片段电量 = 区间电量 × 时长占比（线性分摊）；
        金额 = 片段电量 × 当时生效费率。跨午夜区间按午夜拆成多段。
      </p>
      <table>
        <thead>
          <tr>
            <th>原始读数区间</th><th className="num">表码 起→止</th><th className="num">区间电量</th>
            <th>片段时段</th><th className="num">时长占比</th><th className="num">片段电量</th>
            <th>时段/日类型</th><th>费率版本</th><th className="num">单价</th><th className="num">金额(未舍入)</th>
          </tr>
        </thead>
        <tbody>
          {frags.map((f) => (
            <tr key={f.id}>
              <td className="small mono">
                {fmtDateTime(f.intervalStart)}<br />{fmtDateTime(f.intervalEnd)}
              </td>
              <td className="num mono small">{f.readingStart} → {f.readingEnd}</td>
              <td className="num">{kwh(f.intervalKwh, 6)}</td>
              <td className="small mono">
                {fmtDateTime(f.segmentStart)}<br />{fmtDateTime(f.segmentEnd)}
              </td>
              <td className="num mono small">{new Decimal(f.share).times(100).toFixed(4)}%</td>
              <td className="num">{kwh(f.kwh, 6)}</td>
              <td>
                <span className={`badge ${f.periodType}`}>{f.periodLabel}</span>
                <div className="small muted">{f.dayType === 'WORKDAY' ? '工作日' : f.dayType === 'WEEKEND' ? '周末' : '节假日'}</div>
              </td>
              <td className="mono small">{f.tariffCode}</td>
              <td className="num mono">¥{f.pricePerKwh}</td>
              <td className="num mono">{money(new Decimal(f.kwh).times(f.pricePerKwh).toFixed(12))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="small muted" style={{ marginTop: 8 }}>
        片段金额合计（未舍入）= {money(
          frags.reduce((a, f) => a.plus(new Decimal(f.kwh).times(f.pricePerKwh)), new Decimal(0)).toFixed(12)
        )}，对应费用项未舍入 {money(line.rawAmount)}；该行舍入后 {money(line.amount)}。
      </div>
    </div>
  )
}

function TierDrilldown({ line, detail }: { line: Line; detail: BillDetail }) {
  const allocs = detail.fragments.flatMap((f) =>
    f.tierAllocs
      .filter((a) => a.tierIndex === line.tierIndex && f.tariffId === line.tariffId)
      .map((a) => ({ a, f })))
  return (
    <div className="panel">
      <h2>「{lineName(line)}」阶梯分摊（{allocs.length} 笔）</h2>
      <p className="small muted">
        电量按时间顺序累计进入档位，月累计电量跨档时把同一片段电量拆开；
        “落入前累计”恰好等于上界即边界本身仍属上一档（区间 [下界, 上界)），越界部分进下一档。
        加价费率取片段当时生效版本（月中调价后按新版本加价）。
      </p>
      <table>
        <thead>
          <tr>
            <th>所属电量片段</th><th>时段</th><th>费率版本</th>
            <th className="num">落入该档电量</th><th className="num">落入前月累计</th>
            <th className="num">加价 ¥/kWh</th><th className="num">金额(未舍入)</th>
          </tr>
        </thead>
        <tbody>
          {allocs.map(({ a, f }) => (
            <tr key={a.id}>
              <td className="small mono">{fmtDateTime(f.segmentStart)} → {fmtDateTime(f.segmentEnd)}</td>
              <td><span className={`badge ${f.periodType}`}>{f.periodLabel}</span></td>
              <td className="mono small">{f.tariffCode}</td>
              <td className="num">{kwh(a.kwh, 6)}</td>
              <td className="num mono">{kwh(a.cumulativeBefore, 6)}</td>
              <td className="num mono">+{a.surchargePerKwh}</td>
              <td className="num mono">{money(new Decimal(a.kwh).times(a.surchargePerKwh).toFixed(12))}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="small muted" style={{ marginTop: 8 }}>
        该档电量合计 {kwh(line.kwh, 6)} kWh，未舍入 {money(line.rawAmount)}，舍入后 {money(line.amount)}。
      </div>
    </div>
  )
}

function RoundingDrilldown({ detail }: { detail: BillDetail }) {
  const charge = detail.lines.filter((l) => l.kind !== 'ROUNDING')
  return (
    <div className="panel">
      <h2>舍入差额口径</h2>
      <div className="kv">
        <div className="k">未舍入合计</div>
        <div className="mono">{money(detail.rawTotal)} 元（保留 12 位小数）</div>
        <div className="k">应收金额</div>
        <div className="mono">HALF_UP(未舍入合计 → 分) = {money(detail.totalAmount)} 元</div>
        <div className="k">分项舍入之和</div>
        <div className="mono">
          {charge.map((l) => money(l.amount)).join(' + ')} = {money(detail.roundedLineSum)} 元
        </div>
        <div className="k">舍入差额</div>
        <div className="mono">{money(detail.totalAmount)} − {money(detail.roundedLineSum)} = {money(detail.roundingAmount)} 元</div>
      </div>
      <p className="small muted" style={{ marginTop: 10 }}>
        应收以总额一次舍入为准，分项各舍入到分，二者之差独立列示，保证「分项金额 + 舍入差额 ≡ 应收金额」，每分钱可解释。
      </p>
    </div>
  )
}

function BasisPanel({ detail }: { detail: BillDetail }) {
  const [open, setOpen] = useState(false)
  let pretty = detail.basisSnapshot
  try {
    pretty = JSON.stringify(JSON.parse(detail.basisSnapshot), null, 2)
  } catch {
    /* keep raw */
  }
  return (
    <div className="panel">
      <div className="row" style={{ cursor: 'pointer' }} onClick={() => setOpen(!open)}>
        <h2 style={{ margin: 0 }}>计算依据 {detail.status === 'CONFIRMED' ? '（已冻结快照）' : '（试算快照）'}</h2>
        <span className="spacer" />
        <span className="small mono muted">SHA-256: {detail.basisHash.slice(0, 16)}…</span>
        <span className="small muted">{open ? '收起 ▲' : '展开 ▼'}</span>
      </div>
      {open && (
        <>
          <p className="small muted">
            指纹 = 对完整依据 JSON（表计、账期窗口、费率版本/时段/阶梯、日历、全部参与读数、舍入规则与结果）取 SHA-256。
            {detail.status === 'CONFIRMED'
              ? '账单确认后该快照随账单固化，此后改费率/补读数不影响历史账单。'
              : '试算不保存；点“确认账单”后按同一依据落库冻结。'}
          </p>
          <pre className="snapshot">{pretty}</pre>
        </>
      )}
    </div>
  )
}
