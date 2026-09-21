import { useRef, useState } from 'react'
import { api } from '../api/client'
import type { ImportReport } from '../api/types'

export function ImportBox({ onImported }: { onImported: () => void }) {
  const fileRef = useRef<HTMLInputElement>(null)
  const [report, setReport] = useState<ImportReport | null>(null)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  async function doImport() {
    const f = fileRef.current?.files?.[0]
    if (!f) {
      setErr('请先选择 CSV 文件（meter_code,timestamp,reading_kwh）')
      return
    }
    setBusy(true)
    setErr('')
    try {
      const r = await api.importCsv(f)
      setReport(r)
      onImported()
    } catch (e) {
      setErr((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="row">
        <input ref={fileRef} type="file" accept=".csv,text/csv" />
        <button className="btn" onClick={doImport} disabled={busy}>
          {busy ? '导入中…' : '导入读数（可重复）'}
        </button>
      </div>
      {err && <div className="error small" style={{ marginTop: 6 }}>{err}</div>}
      {report && (
        <div className="small muted" style={{ marginTop: 6 }}>
          共 {report.totalRows} 行：新增 {report.inserted}，更新 {report.updated}
          {report.rejected > 0 && <span className="error">，拒绝 {report.rejected}</span>}
          {report.rejected === 0 && '（幂等：再次导入结果不变）'}
          {report.errors.length > 0 && (
            <ul className="error">
              {report.errors.slice(0, 5).map((e, i) => <li key={i}>{e}</li>)}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
