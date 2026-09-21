import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type {
  BillDetail,
  BillLine,
  BillSummary,
  Draft,
  Fragment,
  StoredFragment,
} from '../api/types'
import { fmtTime } from '../utils/fmt'
import { TracePanel } from '../components/TracePanel'

type TraceItem = {
  label: string
  kind: string
  fragments: Array<Fragment | StoredFragment>
}

export function BillPage({ meterId, month }: { meterId: number; month: string }) {
  const [draft, setDraft] = useState<Draft | null>(null)
  const [history, setHistory] = useState<BillSummary[]>([])
  const [openBill, setOpenBill] = useState<BillDetail | null>(null)
  const [trace, setTrace] = useState<TraceItem | null>(null)
  const [err, setErr] = useState('')
  const [msg, setMsg] = useState('')
  const [busy, setBusy] = useState(false)

  async function loadDraft() {
    if (!meterId) return
    setErr('')
    setTrace(null)
    try {
      setDraft(await api.draft(meterId, month))
    } catch (e) {
      setDraft(null)
      setErr((e as Error).message)
    }
  }

  async function loadHistory() {
    if (!meterId) {
      setHistory([])
      return
    }
    setHistory(await api.bills(meterId))
  }

  useEffect(() => {
    setOpenBill(null)
    loadDraft()
    loadHistory()
  }, [meterId, month])

  async function confirm() {
    if (!meterId) return
    if (!window.confirm(`确认 ${month} 账单？确认后将冻结当时全部计算依据（片段/费率版本/舍入差额），之后改数不影响本账单。`)) return
    setBusy(true)
    try {
      const r = await api.confirm(meterId, month)
      setMsg(`账单 #${r.billId} 已确认并保存快照`)
      await loadHistory()
      await loadDraft()
      const detail = await api.bill(r.billId)
      setOpenBill(detail)
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  async function reverse(id: number) {
    if (!window.confirm('红冲后账单标记 REVERSED（快照保留不删），可重新试算。确认？')) return
    await api.reverse(id)
    setMsg(`账单 #${id} 已红冲`)
    await loadHistory()
    await loadDraft()
    setOpenBill(null)
  }

  if (!meterId) return <div className="muted">请先选择表计。</div>

  return (
    <>
      {err && <div className="callout warn">试算失败：{err}（按口径拒绝猜测，请先修复数据/费率空档）</div>}
      {msg && <div className="callout" style={{ borderColor: '#3a5e3a', background: '#17241a', color: '#bfe3bf' }}>{msg}</div>}

      {!openBill && draft && (
        <DraftView
          draft={draft}
          history={history}
          busy={busy}
          onConfirm={confirm}
          onTrace={(l: BillLine) => setTrace({ label: l.label, kind: l.kind, fragments: l.fragments })}
          traceActive={trace?.label}
          onOpenHistory={async (id) => setOpenBill(await api.bill(id))}
        />
      )}

      {openBill && (
        <ConfirmedBillView bill={openBill} onBack={() => setOpenBill(null)} onReverse={reverse}
          onTrace={(label, kind, frags) => setTrace({ label, kind, fragments: frags })}
          traceActive={trace?.label} />
      )}

      {trace && (
        <TracePanel
          title={trace.label}
          kind={trace.kind}
          fragments={trace.fragments}
          onClose={() => setTrace(null)}
        />
      )}
    </>
  )
}

function DraftView({ draft, history, busy, onConfirm, onTrace, traceActive, onOpenHistory }: {
  draft: Draft
  history: BillSummary[]
  busy: boolean
  onConfirm: () => void
  onTrace: (l: BillLine) => void
  traceActive?: string
  onOpenHistory: (id: number) => void
}) {
  const confirmed = draft.status === 'CONFIRMED'
  return (
    <>
      <div className="panel">
        <div className="row">
          <h2 style={{ margin: 0 }}>
            {draft.meterName} · {draft.billMonth} 试算账单
          </h2>
          <span className={`badge ${draft.status}`}>
            {confirmed ? '已确认冻结' : draft.status === 'REVERSED' ? '历史已红冲' : '试算中（未保存）'}
          </span>
          <div className="spacer" />
          <button className="btn" onClick={onConfirm} disabled={busy || confirmed || draft.gapPresent}>
            {confirmed ? '账单已冻结' : draft.gapPresent ? '存在缺口不可确认' : '确认并保存计算依据'}
          </button>
        </div>

        {draft.gapPresent && (
          <div className="callout">
            {draft.missingSummary}。请补传缺失区间读数后重新试算；缺口电量不按零处理。
          </div>
        )}

        <table style={{ marginTop: 12 }}>
          <thead>
            <tr>
              <th>费用项（点击下钻）</th>
              <th>电量(kWh)</th>
              <th>单价(元/kWh)</th>
              <th>金额(4位)</th>
              <th>金额(分)</th>
            </tr>
          </thead>
          <tbody>
            {draft.lines.map((l) => (
              <tr key={l.label} className="clickable" data-type={l.kind === 'TOU' ? l.label.charAt(0) === '尖' ? 'SHARP' : l.label.charAt(0) === '峰' ? 'PEAK' : l.label.charAt(0) === '谷' ? 'VALLEY' : 'FLAT' : l.kind}
                  onClick={() => onTrace(l)}>
                <td>
                  {l.label}
                  {traceActive === l.label && <span className="pill" style={{ marginLeft: 8 }}>已展开追溯</span>}
                </td>
                <td>{l.kwh}</td>
                <td>{l.unitPrice ?? <span className="muted">多版本混合，见片段</span>}</td>
                <td className="mono">{l.amountRaw}</td>
                <td className="amount mono">{l.amount}</td>
              </tr>
            ))}
            <tr className="total-row">
              <td>合计</td>
              <td>{draft.totalKwh}</td>
              <td />
              <td className="mono">{sumRaw(draft.lines)}</td>
              <td className="amount mono">¥{draft.totalAmount}</td>
            </tr>
          </tbody>
        </table>

        <div className="row" style={{ marginTop: 10 }}>
          <span className="small muted">
            账级舍入差额（4位合计 → 2位 HALF_UP）：
            <span className="mono"> {draft.rawToBillRounding}</span> 元；
            行级平衡项：<span className="mono">{draft.balancingRounding}</span> 元
          </span>
        </div>
      </div>

      <div className="panel">
        <h2>参与本账单的费率版本（生效区间）</h2>
        <table>
          <thead><tr><th>版本</th><th>编号</th><th>生效起</th><th>生效止</th></tr></thead>
          <tbody>
            {draft.versionsUsed.map((v) => (
              <tr key={v.id}>
                <td>{v.code}</td>
                <td>v{v.versionNo} <span className="muted">(id={v.id})</span></td>
                <td className="mono">{fmtTime(v.effectiveFrom)}</td>
                <td className="mono">{v.effectiveTo ? fmtTime(v.effectiveTo) : '至今（开放区间）'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {history.length > 0 && (
        <div className="panel">
          <h2>该表计历史账单</h2>
          <table>
            <thead><tr><th>账月</th><th>状态</th><th>电量</th><th>金额</th><th>缺口说明</th><th></th></tr></thead>
            <tbody>
              {history.map((b) => (
                <tr key={b.id}>
                  <td>{b.billMonth}</td>
                  <td><span className={`badge ${b.status}`}>{b.status}</span></td>
                  <td>{b.totalKwh}</td>
                  <td className="amount mono">¥{b.totalAmount}</td>
                  <td className="small muted">{b.missingInfo ?? '—'}</td>
                  <td>
                    <button className="btn secondary" onClick={() => onOpenHistory(b.id)}>查看快照</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  )
}

function ConfirmedBillView({ bill, onBack, onReverse, onTrace, traceActive }: {
  bill: BillDetail
  onBack: () => void
  onReverse: (id: number) => void
  onTrace: (label: string, kind: string, frags: StoredFragment[]) => void
  traceActive?: string
}) {
  const b = bill.bill
  return (
    <div className="panel">
      <div className="row">
        <h2 style={{ margin: 0 }}>账单快照 #{b.id} · {bill.meterCode} · {b.billMonth}</h2>
        <span className={`badge ${b.status}`}>{b.status}</span>
        <div className="spacer" />
        <button className="btn secondary" onClick={onBack}>返回试算</button>
        {b.status === 'CONFIRMED' && (
          <button className="btn danger" onClick={() => onReverse(b.id)}>红冲</button>
        )}
      </div>
      <p className="small muted" style={{ marginTop: 8 }}>
        以下为确认当时冻结的计算依据；此后修改读数或费率不会改变本账单。
        {b.confirmedAt && <>确认时间：{fmtTime(b.confirmedAt)}。</>}
      </p>
      <table style={{ marginTop: 10 }}>
        <thead><tr><th>费用项（点击下钻）</th><th>电量</th><th>单价</th><th>金额(4位)</th><th>金额(分)</th></tr></thead>
        <tbody>
          {bill.lines.map(({ line, fragments }) => (
            <tr key={line.id} className="clickable"
                onClick={() => onTrace(line.label, line.lineKind, fragments)}>
              <td>{line.label}{traceActive === line.label && <span className="pill" style={{ marginLeft: 8 }}>已展开</span>}</td>
              <td>{line.kwh}</td>
              <td>{line.unitPrice ?? '—'}</td>
              <td className="mono">{line.amountRaw}</td>
              <td className="amount mono">{line.amount}</td>
            </tr>
          ))}
          <tr className="total-row">
            <td>合计</td><td>{b.totalKwh}</td><td />
            <td /><td className="amount mono">¥{b.totalAmount}</td>
          </tr>
        </tbody>
      </table>
      <p className="small muted" style={{ marginTop: 8 }}>保存的舍入差额：<span className="mono">{b.roundingDiff}</span> 元</p>
    </div>
  )
}

function sumRaw(lines: BillLine[]): string {
  const s = lines.reduce((acc, l) => acc + Number(l.amountRaw), 0)
  return s.toFixed(4)
}
