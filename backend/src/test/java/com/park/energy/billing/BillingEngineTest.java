package com.park.energy.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.park.energy.billing.TestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 与 samples/expected-bills.md 手工算例一致的引擎测试。
 * 所有金额十进制定点：片段/行 4 位小数，账单 2 位（HALF_UP），差额单列。
 */
class BillingEngineTest {

    private final BillingEngine engine = new BillingEngine();
    private static final LocalDate MAR = LocalDate.of(2026, 3, 1);

    private BillingEngine.LineSpec line(BillingEngine.BillCalc c, String label) {
        return c.lines().stream().filter(l -> l.label().equals(label)).findFirst().orElseThrow();
    }

    /** M01：全月 TOU，跨午夜谷段。
     *  每天：谷8h×8=64、平4h×5=20、峰8h×3=24、尖4h×2=8，日 116 kWh；
     *  3月31天：谷1984 平620 峰744 尖248，合计3596；电费合计 1934.40。 */
    @Test
    void m01_fullMonthTou_crossesMidnight() {
        var intervals = hourlyMonth(8, 5, 3, 2);
        var v = version(1, 1,
                MAR.atStartOfDay(TZ).toInstant(), null, "1.20", "0.90", "0.60", "0.30");

        var calc = engine.calculate(intervals, MAR, TZ, List.of(v), null);

        assertN("3596", calc.totalKwh());
        assertN("595.2000", line(calc, "谷电费").amountRaw());
        assertN("372.0000", line(calc, "平电费").amountRaw());
        assertN("669.6000", line(calc, "峰电费").amountRaw());
        assertN("297.6000", line(calc, "尖电费").amountRaw());
        assertN("1934.40", calc.totalAmount());
        // 谷段覆盖 22-24 与 00-06 两种窗口，且跨月日期切换
        boolean hasEarlyMorning = line(calc, "谷电费").fragments().stream()
                .anyMatch(f -> f.start().atZone(TZ).getHour() < 6);
        boolean hasLateNight = line(calc, "谷电费").fragments().stream()
                .anyMatch(f -> f.start().atZone(TZ).getHour() == 22);
        assertTrue(hasEarlyMorning && hasLateNight, "跨午夜谷段必须同时包含 22-24 和 00-06");
        // 分毫不差：各行电量合计 = 总电量
        BigDecimal sumKwh = calc.lines().stream().map(BillingEngine.LineSpec::kwh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN("3596.0000", sumKwh);
    }

    /** M02：3/15 00:00 调价 + 阶梯加价。
     *  全天匀速 1 kWh/h：各时段电量 = 小时数。前14天/后17天按版本切分。
     *  TOU 旧：谷112×.4+平56×.6+峰112×.8+尖56×1=224.0；
     *      新：谷136×.45+平68×.7+峰136×1+尖68×1.2=326.4；合计550.40。
     *  阶梯加价（744 kWh）：200×.1+200×.2+344×.3=163.20。总计 713.60。 */
    @Test
    void m02_midMonthRateChangeAndTierSurcharge() {
        var intervals = hourlyMonth(1, 1, 1, 1);
        Instant cut = LocalDate.of(2026, 3, 15).atStartOfDay(TZ).toInstant();
        var v1 = version(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                cut, "1.00", "0.80", "0.60", "0.40");
        var v2 = version(2, 2, cut, null, "1.20", "1.00", "0.70", "0.45");

        var calc = engine.calculate(intervals, MAR, TZ, List.of(v1, v2), surcharge());

        assertN("744", calc.totalKwh());
        assertN("106.0000", line(calc, "谷电费").amountRaw()); // 112×.4+136×.45
        assertN("81.2000", line(calc, "平电费").amountRaw());  // 56×.6+68×.7
        assertN("225.6000", line(calc, "峰电费").amountRaw()); // 112×.8+136×1
        assertN("137.6000", line(calc, "尖电费").amountRaw()); // 56×1+68×1.2
        assertN("20.0000", line(calc, "阶梯加价 第1档(0~200kWh)").amountRaw());
        assertN("40.0000", line(calc, "阶梯加价 第2档(200~400kWh)").amountRaw());
        assertN("103.2000", line(calc, "阶梯加价 第3档(400~∞kWh)").amountRaw());
        assertN("713.60", calc.totalAmount());

        // 版本切分可追溯：谷段 248 kWh = v1 112 + v2 136（3/15 边界在 00:00，恰好落在整点读数）
        var valley = line(calc, "谷电费");
        BigDecimal v1Kwh = valley.fragments().stream()
                .filter(f -> f.rateVersionId() == 1)
                .map(ChargedFragment::allocatedKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal v2Kwh = valley.fragments().stream()
                .filter(f -> f.rateVersionId() == 2)
                .map(ChargedFragment::allocatedKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN("112.0000", v1Kwh);
        assertN("136.0000", v2Kwh);
    }

    /** M03：阶梯全额，总量 502 恰好越过 500 边界 → 500×0.8 + 2×1.0 = 402.00。 */
    @Test
    void m03_tierReplaceExactlyCrossesBoundary() {
        // 3/1 00:00 → 3/22 04:00 每小时 1 kWh，共 21 天 4 小时 = 508 小时；构造到 502
        // 直接用固定增量：前 502 小时每小时 1 kWh
        var intervals = constantHourly(502, 1);
        var v = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                null, "0.99");

        var calc = engine.calculate(intervals, MAR, TZ, List.of(v), replaceTier());

        assertN("502", calc.totalKwh());
        assertN("400.0000", line(calc, "阶梯电费 第1档(0~500kWh)").amountRaw());
        assertN("2.0000", line(calc, "阶梯电费 第2档(500~∞kWh)").amountRaw());
        assertN("402.00", calc.totalAmount());
        // 越界片段追溯：第 502 小时整段应出现在第 2 档；第 501 小时出现在第 1 档
        var step2 = line(calc, "阶梯电费 第2档(500~∞kWh)").fragments();
        BigDecimal k2 = step2.stream().map(ChargedFragment::allocatedKwh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN("2.0000", k2);
    }

    /** 恰好落在边界（总量=500）：第 2 档不得出现任何电量。 */
    @Test
    void tierExactBoundaryStaysInLowerStep() {
        var intervals = constantHourly(500, 1);
        var v = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                null, "0.99");
        var calc = engine.calculate(intervals, MAR, TZ, List.of(v), replaceTier());
        assertN("400.00", calc.totalAmount());
        assertTrue(calc.lines().stream().noneMatch(l -> l.label().contains("第2档")));
    }

    /** 缺失读数不得当零：缺口区间由服务层剔除，引擎只对给到的区间计费。 */
    @Test
    void gapsAreExcludedNotZero() {
        // 构造 3/1 00:00~05:00 的 4 个小时区间各 10 kWh（模拟服务层已剔除 01-05 缺口）
        var intervals = List.of(
                interval("2026-03-01T00:00:00Z", "2026-03-01T01:00:00Z", 10),
                interval("2026-03-01T05:00:00Z", "2026-03-01T06:00:00Z", 10));
        var v = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                null, "0.80");
        var calc = engine.calculate(intervals, MAR, TZ, List.of(v), null);
        assertN("20", calc.totalKwh());   // 缺口 4 小时绝不当 0，也不被臆造
        assertN("16.00", calc.totalAmount());
    }

    /** 费率空档必须直接报错，而不是猜价格。 */
    @Test
    void rateGapIsRejected() {
        var intervals = constantHourly(10, 1);
        Instant cut = LocalDate.of(2026, 3, 15).atStartOfDay(TZ).toInstant();
        Instant restart = LocalDate.of(2026, 3, 16).atStartOfDay(TZ).toInstant();
        var v1 = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(), cut, "0.8");
        var v2 = flat(2, 2, restart, null, "0.9");
        assertThrows(BillingRuleException.class,
                () -> engine.calculate(intervals, MAR, TZ, List.of(v1, v2), null));
    }

    /** 舍入差额：非整分单价导致 4 位→2 位出现差额时必须单列且合计到分。 */
    @Test
    void roundingDifferenceIsTracked() {
        Instant base = MAR.atStartOfDay(TZ).toInstant();
        var v = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                null, "0.005");
        // 3 kWh × 0.005 = 0.0150 → 账单 HALF_UP 0.02；行 0.015→0.02；raw→bill 差额 0.005
        var three = List.of(
                new ReadingInterval(base, base.plusSeconds(3600), bd("0"), bd("1"), bd("1")),
                new ReadingInterval(base.plusSeconds(3600), base.plusSeconds(7200),
                        bd("1"), bd("2"), bd("1")),
                new ReadingInterval(base.plusSeconds(7200), base.plusSeconds(10800),
                        bd("2"), bd("3"), bd("1")));
        var calc3 = engine.calculate(three, MAR, TZ, List.of(v), null);
        assertN("0.02", calc3.totalAmount());
        assertN("0.0050", calc3.rawToBillRounding());
        BigDecimal lineSum = calc3.lines().stream()
                .map(BillingEngine.LineSpec::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN("0.02", lineSum); // 含可能的平衡行后，分级别合计必须等于账单
    }

    /** 舍入记账恒等式：账单总额 = Σ 各行到分金额（含可能的「舍入差额」行）。 */
    @Test
    void billIdentityHoldsWithRounding() {
        Instant base = MAR.atStartOfDay(TZ).toInstant();
        var three = List.of(
                new ReadingInterval(base, base.plusSeconds(3600), bd("0"), bd("1"), bd("1")),
                new ReadingInterval(base.plusSeconds(3600), base.plusSeconds(7200),
                        bd("1"), bd("2"), bd("1")),
                new ReadingInterval(base.plusSeconds(7200), base.plusSeconds(10800),
                        bd("2"), bd("3"), bd("1")));
        var v = flat(1, 1, LocalDate.of(2026, 1, 1).atStartOfDay(TZ).toInstant(),
                null, "0.005");
        var calc = engine.calculate(three, MAR, TZ, List.of(v), null);
        assertN("0.02", calc.totalAmount());              // 3×0.005=0.0150 HALF_UP
        assertN("0.0050", calc.rawToBillRounding());      // 4位→2位 差额留痕
        BigDecimal lineSum = calc.lines().stream()
                .map(BillingEngine.LineSpec::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN(calc.totalAmount().toPlainString(), lineSum); // 恒等（必要时由平衡行兜底）
        // 电量守恒：片段分摊合计 = 总电量（残差兜底保证分毫不差）
        BigDecimal fragSum = calc.lines().stream()
                .flatMap(l -> l.fragments().stream())
                .map(ChargedFragment::allocatedKwh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertN("3.0000", fragSum);
    }

    // ------------------------------------------------------------------

    private List<ReadingInterval> constantHourly(int hours, int kwhEach) {
        Instant base = MAR.atStartOfDay(TZ).toInstant();
        List<ReadingInterval> out = new java.util.ArrayList<>();
        for (int i = 0; i < hours; i++) {
            BigDecimal a = BigDecimal.valueOf((long) i * kwhEach);
            BigDecimal b = BigDecimal.valueOf((long) (i + 1) * kwhEach);
            out.add(new ReadingInterval(base.plusSeconds(3600L * i),
                    base.plusSeconds(3600L * (i + 1)), a, b, BigDecimal.valueOf(kwhEach)));
        }
        return out;
    }

    private ReadingInterval interval(String a, String b, int kwh) {
        return new ReadingInterval(Instant.parse(a), Instant.parse(b),
                bd("0"), bd(String.valueOf(kwh)), bd(String.valueOf(kwh)));
    }

    private void assertN(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "期望 " + expected + " 实际 " + actual);
    }
}
