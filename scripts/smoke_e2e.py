#!/usr/bin/env python3
"""
端到端冒烟：seed → 导入四份 CSV → 试算 → 与 samples/expected-bills.md 手工数字逐分核对
→ 费用项下钻追溯 → 确认快照 → 改读数后确认账单不变 → 红冲。
金额用 Decimal 比较，绝不允许浮点。
"""
import json
import sys
import urllib.request
import urllib.parse
from decimal import Decimal
from pathlib import Path

BASE = "http://localhost:8080/api"
SAMPLES = Path(__file__).resolve().parent.parent / "samples"

fails = []


def check(name, got, want):
    ok = Decimal(str(got)) == Decimal(str(want))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}: got={got} want={want}")
    if not ok:
        fails.append(name)


def get(path):
    with urllib.request.urlopen(BASE + path) as r:
        return json.load(r)


def post(path):
    req = urllib.request.Request(BASE + path, method="POST")
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def import_csv(fname):
    boundary = "----smoke"
    data = (SAMPLES / fname).read_bytes()
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{fname}\"\r\n"
        f"Content-Type: text/csv\r\n\r\n"
    ).encode() + data + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        BASE + "/meters/import", data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def line(draft, label_substr):
    for l in draft["lines"]:
        if label_substr in l["label"]:
            return l
    raise KeyError(label_substr)


def main():
    print(">> seed")
    post("/admin/seed")

    print(">> 导入样本（第一轮）")
    for f in ["readings-tou.csv", "readings-midmonth.csv", "readings-tier.csv", "readings-gap.csv"]:
        rep = import_csv(f)
        assert rep["rejected"] == 0, rep
        print(f"  {f}: +{rep['inserted']} ~{rep['updated']}")

    print(">> 重复导入（幂等：inserted=0）")
    rep = import_csv("readings-tou.csv")
    check("重复导入全部命中更新", rep["inserted"], 0)

    meters = {m["meterCode"]: m["id"] for m in get("/meters")}

    print(">> 算例1 M01：跨午夜 TOU")
    d = get(f"/bills/draft?meterId={meters['M01']}&month=2026-03")
    check("M01 总电量", d["totalKwh"], "3596.0000")
    check("M01 谷", line(d, "谷电费")["amountRaw"], "595.2000")
    check("M01 平", line(d, "平电费")["amountRaw"], "372.0000")
    check("M01 峰", line(d, "峰电费")["amountRaw"], "669.6000")
    check("M01 尖", line(d, "尖电费")["amountRaw"], "297.6000")
    check("M01 账单总额", d["totalAmount"], "1934.40")
    valley = line(d, "谷电费")
    # 片段时间为 UTC：谷段本地 22-06 == UTC 14-22
    hours = sorted({int(f["start"][11:13]) for f in valley["fragments"]})
    assert {14, 15, 16, 17, 18, 19, 20, 21} <= set(hours), hours
    print("  [PASS] 谷段同时覆盖本地 22-24 与 00-06（跨午夜）")

    print(">> 算例2 M02：跨月调价 + 阶梯")
    d = get(f"/bills/draft?meterId={meters['M02']}&month=2026-03")
    check("M02 总电量", d["totalKwh"], "744.0000")
    check("M02 谷(两版本混合)", line(d, "谷电费")["amountRaw"], "106.0000")
    check("M02 平", line(d, "平电费")["amountRaw"], "81.2000")
    check("M02 峰", line(d, "峰电费")["amountRaw"], "225.6000")
    check("M02 尖", line(d, "尖电费")["amountRaw"], "137.6000")
    check("M02 阶梯1", line(d, "第1档")["amountRaw"], "20.0000")
    check("M02 阶梯2", line(d, "第2档")["amountRaw"], "40.0000")
    check("M02 阶梯3", line(d, "第3档")["amountRaw"], "103.2000")
    check("M02 账单总额", d["totalAmount"], "713.60")
    versions = {v["id"] for v in d["versionsUsed"]}
    assert len(versions) == 2, versions
    print("  [PASS] 试算使用两个生效区间版本")

    print(">> 算例3 M03：恰好越过 500 边界")
    d = get(f"/bills/draft?meterId={meters['M03']}&month=2026-03")
    check("M03 总电量", d["totalKwh"], "502.0000")
    check("M03 第1档", line(d, "第1档")["amountRaw"], "400.0000")
    check("M03 第2档(2kWh)", line(d, "第2档")["amountRaw"], "2.0000")
    check("M03 账单总额", d["totalAmount"], "402.00")

    print(">> 算例4 M04：缺失读数不当零")
    d = get(f"/bills/draft?meterId={meters['M04']}&month=2026-03")
    check("M04 可计费电量(739=744-5)", d["totalKwh"], "739.0000")
    check("M04 金额", d["totalAmount"], "591.20")
    assert d["gapPresent"] and d["gaps"][0]["missingMinutes"] == 300, d["gaps"]
    print("  [PASS] 缺口 300 分钟挂起，未当零；账单不可确认（前端按 gapPresent 禁用）")
    curve = get(f"/meters/{meters['M04']}/curve?month=2026-03")
    assert curve["gapPresent"] and len(curve["gaps"]) >= 1
    print("  [PASS] 曲线 API 返回缺口标记")

    print(">> 追溯下钻：M02 谷行片段 Σ 电量/金额")
    d = get(f"/bills/draft?meterId={meters['M02']}&month=2026-03")
    vline = line(d, "谷电费")
    kwh = sum(Decimal(f["allocatedKwh"]) for f in vline["fragments"])
    amt = sum(Decimal(f["allocatedKwh"]) * Decimal(f["unitPrice"]) for f in vline["fragments"])
    check("谷行片段电量合计", kwh, "248.0000")
    check("谷行逐项精确金额合计", amt.quantize(Decimal("0.0001")), "106.0000")
    used = {v["versionNo"]: v["id"] for v in d["versionsUsed"]}
    v1 = sum(Decimal(f["allocatedKwh"]) for f in vline["fragments"] if f["rateVersionId"] == used[1])
    v2 = sum(Decimal(f["allocatedKwh"]) for f in vline["fragments"] if f["rateVersionId"] == used[2])
    check("谷行 v1 电量(前14天)", v1, "112.0000")
    check("谷行 v2 电量(后17天)", v2, "136.0000")

    print(">> 确认 M01 账单并验证快照冻结")
    r = post(f"/bills/confirm?meterId={meters['M01']}&month=2026-03")
    bill_id = r["billId"]
    detail = get(f"/bills/{bill_id}")
    snap_total = detail["bill"]["totalAmount"]
    check("快照总额", snap_total, "1934.40")
    # 改一个读数（幂等 upsert 覆盖）：把 3/1 08:00 表底调低为「前点+1」，
    # 仍保持单调（不会触发倒退校验），但把电量从平段挪到峰段，试算金额必变、快照不变。
    curve = get(f"/meters/{meters['M01']}/curve?month=2026-03")
    # 曲线返回 UTC：本地 3/1 07:00/08:00 == UTC 2/28 23:00 / 3/1 00:00
    p07 = next(p for p in curve["points"] if p["ts"].startswith("2026-02-28T23:00"))
    new_val = f"{Decimal(p07['readingKwh']) + Decimal('1'):.4f}"
    fpath = SAMPLES / "_tmp_mutate.csv"
    fpath.write_text("meter_code,timestamp,reading_kwh\n"
                     f"M01,2026-03-01 08:00:00+08:00,{new_val}\n")
    import_csv("_tmp_mutate.csv")
    detail2 = get(f"/bills/{bill_id}")
    check("改数后已确认账单不变", detail2["bill"]["totalAmount"], "1934.40")
    d2 = get(f"/bills/draft?meterId={meters['M01']}&month=2026-03")
    changed = Decimal(d2["totalAmount"]) != Decimal("1934.40")
    print(f"  [{'PASS' if changed else 'FAIL'}] 改数后新试算金额变化（快照 {snap_total} → 试算 {d2['totalAmount']}）")
    if not changed:
        fails.append("改数应改变试算金额")
    assert d2["status"] == "CONFIRMED", d2["status"]
    blocked = False
    try:
        post(f"/bills/confirm?meterId={meters['M01']}&month=2026-03")
    except urllib.error.HTTPError:
        blocked = True
    assert blocked, "已确认账单必须拒绝重复确认"
    print("  [PASS] 重复确认被拒绝")
    # 还原读数（取原始值：08:00 = 07:00 + 5）
    restore_val = f"{Decimal(p07['readingKwh']) + Decimal('5'):.4f}"
    fpath.write_text("meter_code,timestamp,reading_kwh\n"
                     f"M01,2026-03-01 08:00:00+08:00,{restore_val}\n")
    import_csv("_tmp_mutate.csv")
    fpath.unlink()

    print(">> 红冲后快照仍可查")
    post(f"/bills/{bill_id}/reverse")
    detail3 = get(f"/bills/{bill_id}")
    assert detail3["bill"]["status"] == "REVERSED"
    check("红冲后快照总额仍在", detail3["bill"]["totalAmount"], "1934.40")

    if fails:
        print(f"\nSMOKE FAILED: {len(fails)} -> {fails}")
        sys.exit(1)
    print("\nALL SMOKE CHECKS PASSED")


if __name__ == "__main__":
    main()
