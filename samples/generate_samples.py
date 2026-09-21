#!/usr/bin/env python3
"""
生成可重复导入的样本读数 CSV（累计表底，单调不减）。
口径：整点读数；timestamp 按园区固定时区 Asia/Shanghai 表达（后端据此转 UTC 存储）。

输出：
  samples/readings-tou.csv       M01 2026-03 全月：谷8kWh/h 平5 峰3 尖2（跨午夜谷段）
  samples/readings-midmonth.csv  M02 2026-03 全月：恒定 1 kWh/h（3/15 调价 + 阶梯加价）
  samples/readings-tier.csv      M03 2026-03-01 00:00 起 502 个整点各 1 kWh（恰好越过 500 边界）
  samples/readings-gap.csv       M04 2026-03：正常读数中挖掉 3/10 01:00~05:00（含跨午夜谷段缺口）
同参数重复运行生成字节一致的文件（可重复导入）。
"""
import csv
import os
from datetime import datetime, timedelta

OUT = os.path.dirname(os.path.abspath(__file__))
TZ_SHIFT = "+08:00"

# 与后端 SeedService 的 TOU 日程一致（分钟）
def period_for_hour(h: int) -> str:
    if h >= 22 or h < 6:
        return "V"
    if h < 8 or h == 12 or h == 21:
        return "F"
    if (8 <= h < 10) or (15 <= h < 21):
        return "P"
    return "S"

RATE = {"V": 8, "F": 5, "P": 3, "S": 2}  # M01
MONTH_START = datetime(2026, 3, 1, 0, 0)
MONTH_END = datetime(2026, 4, 1, 0, 0)


def hourly_rows(meter: str, rate: dict[str, int]):
    rows = []
    t = MONTH_START
    reading = 0.0
    # 首点为账月起点表底 0
    rows.append((meter, f"{t:%Y-%m-%d %H:%M:%S}{TZ_SHIFT}", f"{reading:.4f}"))
    while t < MONTH_END:
        h = t.hour
        reading += rate[period_for_hour(h)]
        t += timedelta(hours=1)
        rows.append((meter, f"{t:%Y-%m-%d %H:%M:%S}{TZ_SHIFT}", f"{reading:.4f}"))
    return rows


def constant_rows(meter: str, kwh_per_hour: float, hours: int, start=MONTH_START):
    rows = []
    t = start
    reading = 0.0
    rows.append((meter, f"{t:%Y-%m-%d %H:%M:%S}{TZ_SHIFT}", f"{reading:.4f}"))
    for _ in range(hours):
        reading += kwh_per_hour
        t += timedelta(hours=1)
        rows.append((meter, f"{t:%Y-%m-%d %H:%M:%S}{TZ_SHIFT}", f"{reading:.4f}"))
    return rows


def write(name, rows):
    path = os.path.join(OUT, name)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["meter_code", "timestamp", "reading_kwh"])
        w.writerows(rows)
    print(f"{name}: {len(rows)} rows")


def main():
    write("readings-tou.csv", hourly_rows("M01", RATE))
    write("readings-midmonth.csv", hourly_rows("M02", {k: 1 for k in RATE}))
    write("readings-tier.csv", constant_rows("M03", 1.0, 502))

    # M04：3/1~3/15 半小时? —— 用整点；挖掉 3/10 01:00、02:00、03:00、04:00 的读数
    # （00:00 与 05:00 之间形成 5 小时缺口，落在跨午夜谷段）
    rows = hourly_rows("M04", {k: 1 for k in RATE})
    gap_prefixes = {f"2026-03-10 {h:02d}:00:00{TZ_SHIFT}" for h in (1, 2, 3, 4)}
    rows = [r for r in rows if not r[1].startswith(tuple(gap_prefixes))]
    write("readings-gap.csv", rows)


if __name__ == "__main__":
    main()
