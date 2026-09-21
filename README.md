# 园区电费试算平台 (Park Energy Billing Sandbox)

一个**不接真实电网**的本地试算平台，目标是「能解释每一分钱的来源」。

- 前端：React 18 + Vite + TypeScript（表计曲线、费率日历、账单明细三页）
- 后端：Spring Boot 3（Java 17），全部计费逻辑在后端
- 数据库：PostgreSQL，存储原始读数与费率**生效区间**（SQL 迁移脚本由 Flyway 管理）
- 金额：**十进制定点**（`BigDecimal`/`numeric`），禁止 double；统一保留 4 位小数、账单级 HALF_UP 到 2 位，差额记为「舍入差额」行

## 计费口径（关键业务约定，务必先读）

| 议题 | 明确口径 |
|---|---|
| 原始读数 | 累计表底（kWh，非负，单调不减）。片段电量 = 后一读数 − 前一读数 |
| 缺失读数 | **绝不当零**。某区间缺读数则该区间为「缺口」：不计费、单独挂起，前端曲线用缺口标出，账单展示未计费缺口；可在后续导入补齐后重新试算 |
| 尖峰平谷 | 每个费率版本带按「星期/分钟」的 TOU 日程，**跨午夜时段**按自然日切成两段（如 22:00–06:00 → 当日 22:00–24:00 + 次日 00:00–06:00） |
| 片段切分 | 读数列、TOU 边界、费率版本生效边界三类边界取并集；同一小区间内假设**匀速用电**（电量按分钟数线性分摊） |
| 月中调价 | 费率是 `[effective_from, effective_to)` 的生效区间版本；跨版本的片段按时间比例拆分，各版本各算各的 |
| 月度阶梯 | 按**账月**汇总该月总电量，再判断落在哪一档；档位边界**恰好越过**时多出的部分进高档。账单逐项展示分档明细 |
| 时区 | 单园区固定时区 `Asia/Shanghai`（数据库存 UTC 时刻，账月/TOU 均按园区时区划定） |
| 可重复导入 | 同一 `(meter_id, ts)` 读数幂等 upsert；重复导入同一文件结果不变；导入返回新增/更新/乱序拒绝计数 |
| 已确认账单 | 确认即**快照**：保存当时参与计算的全部片段、费率版本、舍入差额。之后改读数/改费率不影响历史账单；快照不可改，只能红冲后重开试算 |
| 可追溯 | 财务点任一费用项 → 下钻到：参与的电量片段（起止读数/时间）、适用费率版本（id+生效区间+单价）、分摊比例、舍入差额 |

## 样本（手工算例对照）

- `samples/readings-tou.csv`：覆盖跨午夜谷段的读数
- `samples/readings-midmonth.csv`：**跨月调价**（2026-03-15 00:00 调价）
- `samples/readings-tier.csv`：月度用电量**恰好越过阶梯边界**
- `samples/expected-bills.md`：手工算例（逐步数字），与后端 JUnit 测试互相校验

## 快速开始

前置：JDK 17+、Maven 3.9+、Node 20+、PostgreSQL 14+、Flyway（或让后端启动时自动迁移）。

```bash
# 1. 数据库
createdb park_energy
# 也可用 docker：docker run -d --name park-pg -e POSTGRES_DB=park_energy -p 5432:5432 postgres:16

# 2. 后端（默认会自动跑 Flyway；如需种子样本费率，设置 IMPORT_SAMPLE=true）
cd backend
IMPORT_SAMPLE=true ../mvnw spring-boot:run        # 或 mvn spring-boot:run
# API: http://localhost:8080/api

# 3. 前端
cd frontend
npm install && npm run dev                        # http://localhost:5173

# 4. 导入样本（页面上「导入读数」，或脚本）
./scripts/import-samples.sh
```

## 主要 API

- `POST /api/meters/import`（multipart CSV，幂等）
- `GET  /api/meters/{id}/curve?from=&to=`（曲线 + 缺口标记）
- `GET  /api/rates/calendar?month=YYYY-MM`（费率日历/版本时间轴）
- `GET  /api/bills/draft?meterId=&month=YYYY-MM`（试算，不落库）
- `POST /api/bills/{id}/confirm`（确认 → 快照冻结）
- `GET  /api/bills` / `GET /api/bills/{id}`（列表 / 含全部追溯明细）
- `GET  /api/bills/{id}/lines/{lineId}/trace`（费用项 → 片段/费率版本/舍入）

## 目录

```
backend/    Spring Boot 计费服务（核心见 service/billing 包）
frontend/   React 三页应用
samples/    可重复导入的本地 CSV 与手工算例
scripts/    导入脚本
docs/       设计与口径说明
```
