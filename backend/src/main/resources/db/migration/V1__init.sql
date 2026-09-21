-- 园区电费试算平台 初始结构
-- 金额/电量一律使用 PostgreSQL NUMERIC 十进制定点，不使用浮点。

CREATE TABLE meter (
    id              VARCHAR(40) PRIMARY KEY,
    code            VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(128) NOT NULL,
    zone_id         VARCHAR(64),
    timezone        VARCHAR(64) NOT NULL,
    expected_cadence_seconds INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE import_batch (
    id              VARCHAR(40) PRIMARY KEY,
    file_name       VARCHAR(256) NOT NULL,
    content_sha256  VARCHAR(64) NOT NULL UNIQUE,   -- 同文件重复导入直接命中
    row_count       INTEGER NOT NULL,
    imported_rows   INTEGER NOT NULL,
    duplicate_rows  INTEGER NOT NULL,
    invalid_rows    INTEGER NOT NULL,
    errors          TEXT,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE TABLE meter_reading (
    id              VARCHAR(40) PRIMARY KEY,
    meter_id        VARCHAR(40) NOT NULL REFERENCES meter(id),
    ts              TIMESTAMPTZ NOT NULL,          -- 读数时刻（带时区，存 UTC）
    reading_kwh     NUMERIC(18,6) NOT NULL,        -- 累计表底数（单调不减）
    import_batch_id VARCHAR(40) REFERENCES import_batch(id),
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (meter_id, ts)
);
CREATE INDEX idx_reading_meter_ts ON meter_reading (meter_id, ts);

-- 费率版本（生效区间，半开区间 [effective_from, effective_to)）
CREATE TABLE tariff_version (
    id              VARCHAR(40) PRIMARY KEY,
    code            VARCHAR(64) NOT NULL UNIQUE,
    effective_from  TIMESTAMPTZ NOT NULL,
    effective_to    TIMESTAMPTZ,                  -- NULL 表示至今开放
    note            VARCHAR(256),
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tariff_effective ON tariff_version (effective_from, effective_to);

-- 尖峰平谷时段定义；按本地“墙钟分钟”(0..1439) 定义，允许跨午夜 (start_min > end_min)
CREATE TABLE tou_period (
    id                VARCHAR(40) PRIMARY KEY,
    tariff_version_id VARCHAR(40) NOT NULL REFERENCES tariff_version(id) ON DELETE CASCADE,
    period_type       VARCHAR(16) NOT NULL,       -- SHARP/PEAK/FLAT/VALLEY 尖/峰/平/谷
    start_min         INTEGER NOT NULL CHECK (start_min BETWEEN 0 AND 1439),
    end_min           INTEGER NOT NULL CHECK (end_min BETWEEN 1 AND 1440),
    price_per_kwh     NUMERIC(12,6) NOT NULL,     -- 该时段每千瓦时单价（元）
    note              VARCHAR(128)
);
CREATE INDEX idx_tou_version ON tou_period (tariff_version_id);

-- 月度阶梯（同一费率版本内，按累计电量分段）
CREATE TABLE tier_rule (
    id                VARCHAR(40) PRIMARY KEY,
    tariff_version_id VARCHAR(40) NOT NULL REFERENCES tariff_version(id) ON DELETE CASCADE,
    tier_index        INTEGER NOT NULL,           -- 从 1 开始
    upper_kwh         NUMERIC(18,6),              -- 半开区间上界（NULL=无穷）
    surcharge_per_kwh NUMERIC(12,6) NOT NULL,     -- 该段在 TOU 电价之外的加价（元/kWh）
    UNIQUE (tariff_version_id, tier_index)
);

-- 费率日历：日期类型（工作日/周末/节假日），按时区本地日期标记
CREATE TABLE calendar_day (
    id              VARCHAR(40) PRIMARY KEY,
    local_date      DATE NOT NULL,
    day_type        VARCHAR(16) NOT NULL,         -- WORKDAY/WEEKEND/HOLIDAY
    UNIQUE (local_date)
);

-- 已确认账单（每月每表至多一条已确认账单）
CREATE TABLE bill (
    id                VARCHAR(40) PRIMARY KEY,
    meter_id          VARCHAR(40) NOT NULL REFERENCES meter(id),
    billing_month     VARCHAR(7) NOT NULL,        -- yyyy-MM（账期，按时区本地月）
    status            VARCHAR(16) NOT NULL,       -- CONFIRMED
    total_amount      NUMERIC(12,2) NOT NULL,     -- 应收金额（分，定死）
    total_kwh         NUMERIC(18,9) NOT NULL,
    raw_total_amount  NUMERIC(20,12) NOT NULL,    -- 舍入前合计
    rounding_amount   NUMERIC(12,2) NOT NULL,     -- 舍入差额（独立费用项）
    basis_hash        VARCHAR(64) NOT NULL,       -- 计算依据指纹
    basis_snapshot    TEXT NOT NULL,              -- 完整计费依据快照（JSON）
    confirmed_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (meter_id, billing_month)
);

CREATE TABLE bill_line (
    id               VARCHAR(40) PRIMARY KEY,
    bill_id          VARCHAR(40) NOT NULL REFERENCES bill(id) ON DELETE CASCADE,
    line_kind        VARCHAR(16) NOT NULL,        -- ENERGY 能量电费 / TIER 阶梯附加 / ROUNDING 舍入差额
    period_type      VARCHAR(16),                 -- ENERGY: 尖峰平谷
    tier_index       INTEGER,                     -- TIER: 第几档
    tariff_version_id VARCHAR(40),
    tariff_code      VARCHAR(64),
    kwh              NUMERIC(18,9) NOT NULL,
    raw_amount       NUMERIC(20,12) NOT NULL,
    amount           NUMERIC(12,2) NOT NULL,
    line_order       INTEGER NOT NULL
);
CREATE INDEX idx_line_bill ON bill_line (bill_id);

-- 参与计算的电量片段（财务追溯最小单元）
CREATE TABLE bill_fragment (
    id                  VARCHAR(40) PRIMARY KEY,
    bill_id             VARCHAR(40) NOT NULL REFERENCES bill(id) ON DELETE CASCADE,
    bill_line_id        VARCHAR(40) REFERENCES bill_line(id) ON DELETE CASCADE,
    interval_start      TIMESTAMPTZ NOT NULL,
    interval_end        TIMESTAMPTZ NOT NULL,
    reading_start       NUMERIC(18,6) NOT NULL,
    reading_end         NUMERIC(18,6) NOT NULL,
    interval_kwh        NUMERIC(18,9) NOT NULL,  -- 整条原始读数区间电量
    segment_start       TIMESTAMPTZ NOT NULL,     -- 切分后片段（可能是区间的一段）
    segment_end         TIMESTAMPTZ NOT NULL,
    segment_share       NUMERIC(18,12) NOT NULL,  -- 片段时长占原始区间时长比例
    kwh                 NUMERIC(18,9) NOT NULL,   -- 分摊到片段的电量
    period_type         VARCHAR(16) NOT NULL,
    day_type            VARCHAR(16) NOT NULL,
    tariff_version_id   VARCHAR(40) NOT NULL,
    price_per_kwh       NUMERIC(12,6) NOT NULL
);
CREATE INDEX idx_frag_bill ON bill_fragment (bill_id);

-- 阶梯分摊（每片段电量如何落入各档）
CREATE TABLE bill_tier_alloc (
    id                  VARCHAR(40) PRIMARY KEY,
    bill_id             VARCHAR(40) NOT NULL REFERENCES bill(id) ON DELETE CASCADE,
    fragment_id         VARCHAR(40) NOT NULL REFERENCES bill_fragment(id) ON DELETE CASCADE,
    tier_index          INTEGER NOT NULL,
    kwh                 NUMERIC(18,9) NOT NULL,
    cumulative_before   NUMERIC(18,9) NOT NULL,  -- 落在该档前的月累计电量
    surcharge_per_kwh   NUMERIC(12,6) NOT NULL
);
CREATE INDEX idx_alloc_frag ON bill_tier_alloc (fragment_id);

-- 缺失读数缺口（不当作零，显式登记，从计费中排除）
CREATE TABLE bill_gap (
    id              VARCHAR(40) PRIMARY KEY,
    bill_id         VARCHAR(40) NOT NULL REFERENCES bill(id) ON DELETE CASCADE,
    gap_start       TIMESTAMPTZ NOT NULL,
    gap_end         TIMESTAMPTZ NOT NULL,
    reason          VARCHAR(64) NOT NULL,         -- MISSING_READING / BEFORE_FIRST / AFTER_LAST
    detail          VARCHAR(256)
);
CREATE INDEX idx_gap_bill ON bill_gap (bill_id);
