# 园区电费试算平台

一个能解释**每一分钱来源**的电费试算平台：表计曲线、费率日历、账单明细与财务追溯。

- **前端**：React 18 + TypeScript + Vite（decimal.js 定点；自研 SVG 曲线，无重型图表库）
- **后端**：Spring Boot 3.3 + Spring Data JPA + Flyway
- **数据库**：PostgreSQL 15（金额/电量一律 `NUMERIC` 十进制定点）
- **金额**：后端全程 `BigDecimal`（电量 9 位、未舍入金额 12 位、应收 2 位，HALF_UP）；
  前端所有金额/电量以**字符串**接收并用 decimal.js 处理，绝不经过 JS `number`
- **范围**：单园区固定时区 `Asia/Shanghai`；本地读数文件，不接真实电网

## 快速开始（无需 root，本机已内置便携 JDK/Maven/PostgreSQL）

```bash
bash scripts/start-all.sh      # 启动 PG(5439) + Spring Boot(8080) + Vite(5173)
# 前端 http://127.0.0.1:5173 ；后端 http://127.0.0.1:8080/api/meta
bash scripts/stop.sh           # 停止
```

首次启动自动建表并幂等播种：2 块表计、3 个费率版本（含跨月调价与月中调价）、费率日历、两份样本读数。

## 样本账期（建议先看这两个，对照手工算例）

| 表计 | 账期 | 看点 |
|---|---|---|
| M-001 | 2026-02 | 2h 抄表；跨午夜谷段；03-01 跨月调价边界；漏抄 09:00 形成缺失（12kWh 排除，**不当零**） |
| M-001 | 2026-03 | 同一跨午夜区间在新账期侧适用新价；账期末 AFTER_LAST |
| M-002 | 2026-03 | 90min 抄表；3/15 月中调价；月累计**恰好越过 50kWh 阶梯边界**（50 一档 + 4 二档）；应收 **35.69** |

完整逐步推导见 [`docs/manual-calculation.md`](docs/manual-calculation.md)，并由
`BillingEngineTest` 锁定（跨午夜、跨月调价、月中调价、恰好越档、缺失非零、表码倒走、费率缺口、舍入差额）。

## 关键处理口径

1. **缺失读数不是零**：相邻读数间隔 > 抄表节奏 × 1.5（M-001 为 3h，M-002 为 2.25h）即判定缺失，
   整段不计费，在曲线页红色虚线标示、账单页登记缺口（`MISSING_READING/BEFORE_FIRST/AFTER_LAST`）。
2. **区间切分**：相邻读数区间按 账期边界 → 费率版本生效边界 → 本地午夜 → 尖峰平谷边界 依次切分，
   片段电量按秒数线性分摊。跨午夜时段用 `startMin > endMin` 表示（谷 22:00–次日06:00）。
3. **月中调价**：费率版本半开生效区间 `[from,to)`；区间跨切换点时两侧各按其价；覆盖不连续直接报错。
4. **月度阶梯**：片段按时间顺序累计入档，跨档瞬间把同一电量拆入两档；边界值本身属上一档（`[下界,上界)`）；
   加价取片段当时生效版本。
5. **舍入差额**：应收 = 未舍入总额 HALF_UP 到分；每个费用项各自舍入到分；
   `舍入差额 = 应收 − 分项舍入之和`，单列一行，保证恒等。
6. **账单冻结**：确认时把全部依据（表计、窗口、费率/时段/阶梯、日历、参与读数、规则、结果）序列化 JSON
   并计算 SHA-256 指纹，随账单持久化。之后改价/补读数**不影响**历史账单。同表同月重复确认幂等返回。

## 财务追溯路径

账单页点任一费用行 →
- 能量电费行：参与计算的**电量片段**（原始读数区间、表码、时长占比、片段电量、时段/日类型、费率版本、单价、未舍入金额）
- 阶梯行：每笔**阶梯分摊**（落入哪档、落入前月累计、版本加价、对应片段）
- 舍入差额行：总额舍入与分项舍入的完整恒等式
- 底部「计算依据」：完整快照 JSON + SHA-256 指纹（已确认账单取自冻结数据）

## 读数文件导入（可重复）

界面「表计曲线 / 读数导入」选择 CSV，或：

```bash
curl -F "file=@your.csv" http://127.0.0.1:8080/api/readings/import
```

CSV 格式：`meterCode,ts,readingKwh`（表头可选；`ts` 为 ISO 偏移时间，或不带偏移时按 Asia/Shanghai 解释）。

- 按 **SHA-256** 幂等：同一文件重复导入直接命中首次批次，不重复写库；
- 任一行非法（时间格式错、负值、未知表计）→ **整批回滚**，返回逐行错误，绝不静默置零或部分写入；
- 表码倒走在计费时显式报错拒绝。

样本文件：`data/samples/m001_readings.csv`、`data/samples/m002_readings.csv`。

## 目录

```
backend/   Spring Boot（计费引擎 com.park.energy.billing 与框架解耦，可单测对照手工算例）
frontend/  React（MeterCurve 曲线 / TariffCalendar 费率日历 / BillTrace 账单追溯）
data/      PostgreSQL 数据目录 + 样本 CSV（不入库真实电网）
docs/      manual-calculation.md 手工算例
scripts/   start-pg.sh / start-all.sh / stop.sh
```

## 重置

```bash
bash scripts/stop.sh
rm -rf data/pgdata         # 删除数据库（Flyway 会在下次启动重建并重新播种）
```
