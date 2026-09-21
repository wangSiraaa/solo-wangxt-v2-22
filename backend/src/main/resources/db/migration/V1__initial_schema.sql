-- 园区电费试算平台：原始读数 + 费率生效区间 + 账单快照
-- 金额一律 numeric(12,4)，账单汇总 numeric(12,2)；所有时间戳存 UTC (timestamptz)

create table meter (
    id           bigserial primary key,
    meter_code   text not null unique,          -- 表计编号（导入文件用它匹配）
    display_name text not null,
    park_tz      text not null default 'Asia/Shanghai',
    expected_interval_minutes int,           -- 预期采样间隔；相邻读数间隔 > 1.5 倍即判缺口
    created_at   timestamptz not null default now()
);

-- 原始累计表底读数（kWh）。绝不存“缺失行=0”，缺失就是没有行。
create table meter_reading (
    id          bigserial primary key,
    meter_id    bigint not null references meter(id),
    ts          timestamptz not null,           -- 读取时刻（UTC）
    reading_kwh numeric(12,4) not null check (reading_kwh >= 0),
    source_file text,                           -- 来源文件名（可重复导入审计）
    imported_at timestamptz not null default now(),
    unique (meter_id, ts)
);
create index idx_reading_meter_ts on meter_reading(meter_id, ts);

-- 费率版本：[effective_from, effective_to) 生效区间（按园区时区解释边界时刻）
-- 调价 = 插入新版本并关闭旧版本；版本一经账单快照引用即永久保留。
create table rate_version (
    id              bigserial primary key,
    code            text not null,              -- 费率方案代码，如 TOU_DEFAULT
    version_no      int not null,
    effective_from  timestamptz not null,
    effective_to    timestamptz,                -- null = 至今
    base_price      numeric(12,6),              -- 度电基准（TOU 时仅展示，可空）
    is_tou          boolean not null default true,
    flat_price      numeric(12,6),              -- 非 TOU 时的单一单价
    note            text,
    created_at      timestamptz not null default now(),
    unique (code, version_no)
);
create index idx_rate_version_code_range on rate_version(code, effective_from, effective_to);

-- TOU 时段定义：按星期位图 + 园区本地「分钟数」；允许跨午夜（start_min > end_min 表示跨日）
create table tou_period (
    id               bigserial primary key,
    rate_version_id  bigint not null references rate_version(id) on delete cascade,
    period_type      text not null check (period_type in ('SHARP','PEAK','FLAT','VALLEY')),
    dow_mask         int not null check (dow_mask between 1 and 127), -- bit0=周一 … bit6=周日
    start_min        int not null check (start_min between 0 and 1439),
    end_min          int not null check (end_min between 1 and 1440), -- 1440=24:00
    price            numeric(12,6) not null check (price >= 0)
);
-- 不变量：start_min <> end_min；跨午夜 start_min > end_min；应用层展开为两段。
create index idx_tou_period_version on tou_period(rate_version_id);

-- 月度阶梯：按账月总电量分档（度数），价格为「度电加价」或「整档单价」由 mode 决定
create table tier_schedule (
    id               bigserial primary key,
    code             text not null,
    version_no       int not null,
    effective_from   date not null,             -- 账月 >= 该日期(每月1号)生效
    effective_to     date,                      -- null=至今
    pricing_mode     text not null default 'SURCHARGE'
                       check (pricing_mode in ('SURCHARGE','REPLACE')), -- 阶梯加价 / 全额替代
    unique (code, version_no)
);

create table tier_step (
    id               bigserial primary key,
    tier_schedule_id bigint not null references tier_schedule(id) on delete cascade,
    step_no          int not null,              -- 从 1 开始
    lower_kwh        numeric(12,2) not null,    -- 本档下界（含）
    upper_kwh        numeric(12,2),             -- 本档上界（不含），null=无限
    price            numeric(12,6) not null,    -- 该档度电单价
    check (upper_kwh is null or upper_kwh > lower_kwh)
);

-- 表计 → 费率方案 / 阶梯方案（当前绑定；历史以账单快照为准）
create table meter_contract (
    meter_id           bigint primary key references meter(id),
    rate_code          text not null,
    tier_code          text,                       -- null = 不启用月度阶梯
    updated_at         timestamptz not null default now()
);

-- 账单：草稿可反复试算不落库；一旦 CONFIRMED 即冻结
create table bill (
    id              bigserial primary key,
    meter_id        bigint not null references meter(id),
    bill_month      date not null,              -- 账月第一天（园区时区）
    status          text not null check (status in ('DRAFT','CONFIRMED','REVERSED')),
    total_kwh       numeric(12,4) not null,
    total_amount    numeric(12,2) not null,     -- 财务口径，HALF_UP
    rounding_diff   numeric(12,4) not null default 0, -- 明细4位之和 -> 2位 的舍入差额
    missing_info    text,                       -- 未计费缺口摘要
    confirmed_at    timestamptz,
    created_at      timestamptz not null default now(),
    unique (meter_id, bill_month)
);

-- 账单费用行（TOU 行 / 阶梯行 / 舍入差额行）
create table bill_line (
    id            bigserial primary key,
    bill_id       bigint not null references bill(id) on delete cascade,
    line_kind     text not null check (line_kind in ('TOU','TIER','ROUNDING')),
    label         text not null,                -- 如「尖峰电费」「第2档(200~400)」
    kwh           numeric(12,4) not null,
    unit_price    numeric(12,6),
    amount_raw    numeric(12,4) not null,       -- 4 位精度金额
    amount        numeric(12,2) not null,       -- 行级 HALF_UP
    sort_no       int not null
);
create index idx_bill_line_bill on bill_line(bill_id);

-- 账单快照：每个费用行参与计算的电量片段
create table bill_fragment (
    id                 bigserial primary key,
    bill_line_id       bigint not null references bill_line(id) on delete cascade,
    start_ts           timestamptz not null,
    end_ts             timestamptz not null,
    start_reading      numeric(12,4) not null,  -- 区间起点表底
    end_reading        numeric(12,4) not null,  -- 区间终点表底
    full_kwh           numeric(12,4) not null,  -- 原读数区间总电量
    allocated_kwh      numeric(12,4) not null,  -- 分摊到本费用行的电量
    allocate_ratio     numeric(14,10) not null, -- 分摊比例（分钟占比）
    period_type        text,                    -- SHARP/PEAK/FLAT/VALLEY（TIER 行为空）
    tier_step_no       int,                     -- TIER 行片段所属档位
    rate_version_id    bigint references rate_version(id),
    unit_price         numeric(12,6) not null,
    check (end_ts > start_ts),
    check (allocate_ratio between 0 and 1)
);
create index idx_bill_fragment_line on bill_fragment(bill_line_id);
