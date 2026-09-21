import { useEffect, useState } from 'react'
import { api } from './api'
import type { Meter, Curve, TariffVersion, CalendarDay, BillDetail, BillSummary, ImportResult } from './api'
import { MeterCurve } from './MeterCurve'
import { TariffCalendar } from './TariffCalendar'
import { BillTrace } from './BillTrace'
import { money, kwh } from './decimal'
import { fmtDateTime } from './tz'

type Tab = 'curve' | 'tariff' | 'billing' | 'bills'

export default function App() {
  const [tab, setTab] = useState<Tab>('billing')
  const [meters, setMeters] = useState<Meter[]>([])
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    api.meters().then(setMeters).catch((e) => setErr(String(e.message ?? e)))
  }, [])

  return (
    <div>
      <header className="app-header">
        <h1>园区电费试算平台</h1>
        <span className="sub">十进制定点 · 尖峰平谷 + 月度阶梯 · 缺失不当零 · 每分钱可追溯（单园区 Asia/Shanghai）</span>
      </header>
      <nav className="tabs">
        <button className={tab === 'billing' ? 'active' : ''} onClick={() => setTab('billing')}>电费试算</button>
        <button className={tab === 'curve' ? 'active' : ''} onClick={() => setTab('curve')}>表计曲线 / 读数导入</button>
        <button className={tab === 'tariff' ? 'active' : ''} onClick={() => setTab('tariff')}>费率日历</button>
        <button className={tab === 'bills' ? 'active' : ''} onClick={() => setTab('bills')}>已确认账单</button>
      </nav>
      <main>
        {err && <div className="toast-error">后端连接失败：{err}</div>}
        {tab === 'billing' && <BillingTab meters={meters} />}
        {tab === 'curve' && <CurveTab meters={meters} onChanged={() => api.meters().then(setMeters)} />}
        {tab === 'tariff' && <TariffTab />}
        {tab === 'bills' && <BillsTab />}
      </main>
    </div>
  )
}

function CurveTab({ meters, onChanged }: { meters: Meter[]; onChanged: () => void }) {
  const [meterCode, setMeterCode] = useState<string>('')
  const [curve, setCurve] = useState<Curve | null>(null)
  const [importRes, setImportRes] = useState<ImportResult | null>(null)
  const [importErr, setImportErr] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)

  useEffect(() => {
    if (!meterCode && meters[0]) setMeterCode(meters[0].code)
  }, [meters, meterCode])

  useEffect(() => {
    if (meterCode) api.curve(meterCode).then(setCurve).catch(() => setCurve(null))
  }, [meterCode, importRes])

  async function upload(file: File | null) {
    if (!file) return
    setUploading(true)
    setImportErr(null)
    setImportRes(null)
    try {
      const r = await api.importReadings(file)
      setImportRes(r)
      onChanged()
    } catch (e) {
      setImportErr(String((e as Error).message))
    } finally {
      setUploading(false)
    }
  }

  return (
    <div>
      <div className="panel">
        <div className="row">
          <h2 style={{ margin: 0 }}>表计</h2>
          <select value={meterCode} onChange={(e) => setMeterCode(e.target.value)}>
            {meters.map((m) => <option key={m.code} value={m.code}>{m.code} — {m.name}</option>)}
          </select>
          {curve && <span className="small muted">抄表节奏 {curve.points[0] ? '' : ''}
            {meters.find((m) => m.code === meterCode)?.expectedCadenceSeconds}s（缺失判定阈值 = 节奏 × 1.5）
          </span>}
          <span className="spacer" />
          <label className="small muted">可重复导入本地读数 CSV（meterCode,ts,readingKwh）：</label>
          <input type="file" accept=".csv" disabled={uploading}
            onChange={(e) => upload(e.target.files?.[0] ?? null)} />
        </div>
        {importRes && (
          <div className="toast-ok" style={{ marginTop: 12 }}>
            {importRes.reused ? '幂等命中：该文件此前已导入（SHA-256 相同），未重复写入。' : '导入完成。'}
            文件 {importRes.fileName}：数据行 {importRes.rowCount}，新写入 {importRes.importedRows}，
            重复跳过 {importRes.duplicateRows}，非法 {importRes.invalidRows}。
          </div>
        )}
        {importErr && (
          <div className="toast-error" style={{ marginTop: 12 }}>导入被拒绝（整批回滚，未写入任何数据）：{'\n'}{importErr}</div>
        )}
      </div>
      <div className="panel">
        <h2>累计表底曲线</h2>
        {curve && <MeterCurve curve={curve} />}
      </div>
      {curve && curve.points.length > 0 && (
        <div className="panel">
          <h2>原始读数（{curve.points.length} 条）</h2>
          <div style={{ maxHeight: 260, overflow: 'auto' }}>
            <table>
              <thead><tr><th>时刻（{meters.find((m) => m.code === meterCode)?.timezone}）</th><th className="num">累计读数 kWh</th></tr></thead>
              <tbody>
                {curve.points.map((p, i) => (
                  <tr key={i}><td className="mono small">{fmtDateTime(p.ts)}</td><td className="num mono">{p.readingKwh}</td></tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}

function TariffTab() {
  const [versions, setVersions] = useState<TariffVersion[]>([])
  const [month, setMonth] = useState('2026-03')
  const [calendar, setCalendar] = useState<CalendarDay[]>([])

  useEffect(() => {
    api.tariffs().then(setVersions)
  }, [])
  useEffect(() => {
    api.calendar(month).then(setCalendar)
  }, [month])

  return <TariffCalendar versions={versions} calendar={calendar} month={month} onMonth={setMonth} />
}

function BillingTab({ meters }: { meters: Meter[] }) {
  const [meterCode, setMeterCode] = useState('M-002')
  const [month, setMonth] = useState('2026-03')
  const [detail, setDetail] = useState<BillDetail | null>(null)
  const [selectedLine, setSelectedLine] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [errMsg, setErrMsg] = useState<string | null>(null)

  async function doPreview() {
    setBusy(true)
    setErrMsg(null)
    try {
      setDetail(await api.preview(meterCode, month))
      setSelectedLine(null)
    } catch (e) {
      setErrMsg(String((e as Error).message))
      setDetail(null)
    } finally {
      setBusy(false)
    }
  }

  async function doConfirm() {
    if (!confirm(`确认 ${meterCode} 的 ${month} 账单？确认后计算依据将被冻结保存，不可修改。`)) return
    setBusy(true)
    setErrMsg(null)
    try {
      setDetail(await api.confirm(meterCode, month))
      setSelectedLine(null)
    } catch (e) {
      setErrMsg(String((e as Error).message))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="panel">
        <div className="row">
          <h2 style={{ margin: 0 }}>试算条件</h2>
          <select value={meterCode} onChange={(e) => setMeterCode(e.target.value)}>
            {meters.map((m) => <option key={m.code} value={m.code}>{m.code} — {m.name}</option>)}
          </select>
          <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
          <button className="btn" disabled={busy} onClick={doPreview}>试算</button>
          <button className="btn primary" disabled={busy} onClick={doConfirm}>确认账单（冻结依据）</button>
          <span className="spacer" />
          <span className="small muted">建议样本：M-001 / 2026-02（跨午夜+跨月边界+缺失）；M-002 / 2026-03（月中调价+恰好越 50kWh 档）</span>
        </div>
      </div>
      {errMsg && <div className="toast-error">{errMsg}</div>}
      {detail && (
        <BillTrace detail={detail} selectedLineId={selectedLine} onSelectLine={(id) => setSelectedLine(id)} />
      )}
    </div>
  )
}

function BillsTab() {
  const [bills, setBills] = useState<BillSummary[]>([])
  const [detail, setDetail] = useState<BillDetail | null>(null)
  const [selectedLine, setSelectedLine] = useState<string | null>(null)

  function load() {
    api.bills().then(setBills)
  }
  useEffect(load, [])

  async function trace(id: string) {
    setDetail(await api.trace(id))
    setSelectedLine(null)
  }

  if (detail) {
    return (
      <div>
        <button className="btn" onClick={() => setDetail(null)} style={{ marginBottom: 12 }}>← 返回账单列表</button>
        <BillTrace detail={detail} selectedLineId={selectedLine} onSelectLine={setSelectedLine} />
      </div>
    )
  }

  return (
    <div className="panel">
      <div className="row">
        <h2 style={{ margin: 0 }}>已确认账单</h2>
        <span className="spacer" />
        <button className="btn" onClick={load}>刷新</button>
      </div>
      {bills.length === 0 && <div className="muted small" style={{ marginTop: 12 }}>还没有已确认账单，去“电费试算”确认一张。</div>}
      {bills.length > 0 && (
        <table style={{ marginTop: 10 }}>
          <thead>
            <tr><th>表计</th><th>账期</th><th>状态</th><th className="num">计费电量</th>
              <th className="num">舍入差额</th><th className="num">应收金额</th><th>确认时间</th><th></th></tr>
          </thead>
          <tbody>
            {bills.map((b) => (
              <tr key={b.billId}>
                <td className="mono">{b.meterCode}</td>
                <td>{b.billingMonth}</td>
                <td><span className="badge CONFIRMED">已确认</span></td>
                <td className="num">{kwh(b.totalKwh, 3)}</td>
                <td className="num mono">{money(b.roundingAmount)}</td>
                <td className="num mono">¥{money(b.totalAmount)}</td>
                <td className="small">{fmtDateTime(b.confirmedAt)}</td>
                <td><button className="btn" onClick={() => trace(b.billId)}>财务追溯</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
