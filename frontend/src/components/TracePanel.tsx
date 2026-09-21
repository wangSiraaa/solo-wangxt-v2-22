import type { Fragment, StoredFragment } from '../api/types'
import { fmtTime, PERIOD_COLOR, PERIOD_NAME } from '../utils/fmt'

type AnyFragment = Fragment | StoredFragment

/** 财务下钻面板：费用项 → 参与计算的电量片段、费率版本、单价、分摊比例与逐项金额。 */
export function TracePanel({ title, kind, fragments, onClose }: {
  title: string
  kind: string
  fragments: AnyFragment[]
  onClose: () => void
}) {
  const periodOf = (f: AnyFragment) =>
    'periodType' in f ? (f as Fragment).periodType : null
  const isTier = kind === 'TIER' || title.startsWith('阶梯')
  const versions = Array.from(new Set(fragments.map((f) => f.rateVersionId).filter(Boolean)))

  return (
    <div className="panel trace-panel">
      <div className="row">
        <h2 style={{ margin: 0 }}>
          追溯：{title}
          <span className="pill" style={{ marginLeft: 10 }}>{fragments.length} 个电量片段</span>
        </h2>
        <div className="spacer" />
        <button className="btn secondary" onClick={onClose}>收起</button>
      </div>

      <ul className="small muted" style={{ marginTop: 10, lineHeight: 1.8 }}>
        <li>
          每个片段来自两个相邻<strong>有效读数</strong>之间；起点/终点表底之差为区间总电量（full_kwh）。
          缺口区间不会出现（缺失读数不当零）。
        </li>
        <li>
          片段被费率版本边界、账月边界、TOU 时段边界切分；
          <span className="mono"> allocated_kwh = full_kwh × 分摊比例（分钟占比）</span>，
          片内匀速用电，残差补给末段保证 Σ 分毫不差。
        </li>
        <li>
          适用费率版本：
          {versions.length ? versions.map((v) => ` #${v}`).join('，') : ' 无'}
          （版本定义见「费率日历」页；账单快照永久保留版本 id）。
        </li>
        {isTier && (
          <li>
            阶梯费用按账月总电量顺序吃档，边界恰好落在片段中间时该片段按电量拆成两段，分属相邻档位；
            同一物理电量在「加价模式」下会同时出现在 TOU 行与阶梯行，并非重复计费。
          </li>
        )}
        <li>逐项金额 = 分摊电量 × 单价（未逐片段舍入）；行金额为逐项精确求和后 4 位舍入，账单再 HALF_UP 到分。</li>
      </ul>

      <div style={{ overflowX: 'auto', marginTop: 10 }}>
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>片段起</th>
              <th>片段止</th>
              <th>起表底</th>
              <th>止表底</th>
              <th>区间电量</th>
              <th>分摊比例</th>
              <th>分摊电量</th>
              <th>时段/档</th>
              <th>费率版本</th>
              <th>单价</th>
              <th>逐项金额(4位)</th>
              <th>备注</th>
            </tr>
          </thead>
          <tbody>
            {fragments.map((f, i) => {
              const pt = periodOf(f)
              const amount = mul(f.allocatedKwh, f.unitPrice)
              return (
                <tr key={i}>
                  <td>{i + 1}</td>
                  <td className="mono">{fmtTime(f.start)}</td>
                  <td className="mono">{fmtTime(f.end)}</td>
                  <td>{f.startReading}</td>
                  <td>{f.endReading}</td>
                  <td>{f.fullKwh}</td>
                  <td className="mono">{f.allocateRatio}</td>
                  <td className="amount">{f.allocatedKwh}</td>
                  <td style={pt ? { color: PERIOD_COLOR[pt] } : undefined}>
                    {pt ? PERIOD_NAME[pt] : isTier ? `第${(f as Fragment).tierStepNo ?? ''}档` : '—'}
                  </td>
                  <td className="mono">{f.rateVersionId ?? '—'}</td>
                  <td>{f.unitPrice}</td>
                  <td className="mono">{amount}</td>
                  <td className="small muted">
                    {'clipped' in f && (f as Fragment).clipped ? '跨账月边界裁剪' : ''}
                  </td>
                </tr>
              )
            })}
            <tr className="total-row">
              <td colSpan={7}>合计</td>
              <td className="amount">{sum(fragments.map((f) => f.allocatedKwh)).toFixed(4)}</td>
              <td />
              <td />
              <td />
              <td className="mono">{sum(fragments.map((f) => mul(f.allocatedKwh, f.unitPrice))).toFixed(4)}</td>
              <td />
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  )
}

function mul(a: string, b: string): string {
  // 前端仅展示：用整数定点避免浮点误差（电量最多4位、单价最多6位，统一补齐到 8 位后乘积为 10^16）
  const toInt = (s: string) => {
    const [i, d = ''] = s.split('.')
    return BigInt(i + d.padEnd(8, '0').slice(0, 8))
  }
  const n = toInt(a) * toInt(b)
  const SCALE = 16n
  const WANT = 4n
  const divisor = 10n ** (SCALE - WANT)
  const scaled = n / divisor + ((n % divisor) * 2n >= divisor ? 1n : 0n) // HALF_UP 到 4 位
  const whole = scaled / 10000n
  const frac = (scaled % 10000n).toString().padStart(4, '0')
  return `${whole}.${frac}`
}

function sum(xs: string[]): number {
  return xs.reduce((acc, x) => acc + Number(x), 0)
}
