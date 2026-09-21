import { useEffect, useState } from 'react'
import { api } from './api/client'
import type { Meter } from './api/types'
import { CurvePage } from './pages/CurvePage'
import { CalendarPage } from './pages/CalendarPage'
import { BillPage } from './pages/BillPage'
import { ImportBox } from './components/ImportBox'

type Tab = 'curve' | 'calendar' | 'bill'

export function App() {
  const [tab, setTab] = useState<Tab>('curve')
  const [meters, setMeters] = useState<Meter[]>([])
  const [meterId, setMeterId] = useState<number>(0)
  const [month, setMonth] = useState('2026-03')
  const [error, setError] = useState('')
  const [seeded, setSeeded] = useState(false)

  async function loadMeters() {
    try {
      const list = await api.meters()
      setMeters(list)
      if (list.length && !list.some((m) => m.id === meterId)) {
        setMeterId(list[0].id)
      }
      setError('')
    } catch (e) {
      setError(`无法连接后端：${(e as Error).message}（请确认 Spring Boot 已启动且已执行初始化）`)
    }
  }

  useEffect(() => {
    loadMeters()
  }, [])

  async function ensureSeed() {
    await api.seed()
    setSeeded(true)
    await loadMeters()
  }

  return (
    <>
      <header className="topbar">
        <h1>园区电费试算平台</h1>
        <nav className="tabs">
          <button className={tab === 'curve' ? 'active' : ''} onClick={() => setTab('curve')}>
            表计曲线
          </button>
          <button className={tab === 'calendar' ? 'active' : ''} onClick={() => setTab('calendar')}>
            费率日历
          </button>
          <button className={tab === 'bill' ? 'active' : ''} onClick={() => setTab('bill')}>
            账单明细
          </button>
        </nav>
        <div className="spacer" />
        <button className="btn secondary" onClick={ensureSeed}>
          {seeded ? '已初始化（可重复执行）' : '初始化样本费率'}
        </button>
      </header>

      <main>
        {error && <div className="callout warn">{error}</div>}

        {tab !== 'calendar' && (
          <div className="panel">
            <div className="row">
              <div>
                <label>表计</label>
                <br />
                <select
                  value={meterId}
                  onChange={(e) => setMeterId(Number(e.target.value))}
                  style={{ minWidth: 300 }}
                >
                  {meters.length === 0 && <option value={0}>（请先初始化并导入读数）</option>}
                  {meters.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.meterCode} · {m.displayName}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label>账月</label>
                <br />
                <input type="month" value={month} onChange={(e) => setMonth(e.target.value)} />
              </div>
              <div className="spacer" />
              <ImportBox onImported={loadMeters} />
            </div>
          </div>
        )}

        {tab === 'curve' && <CurvePage meterId={meterId} month={month} />}
        {tab === 'calendar' && <CalendarPage month={month} />}
        {tab === 'bill' && <BillPage meterId={meterId} month={month} />}
      </main>
    </>
  )
}
