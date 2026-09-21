package com.park.energy.billing;

import com.park.energy.billing.BillingModels.*;
import com.park.energy.domain.DayType;
import com.park.energy.domain.PeriodType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对照 docs/manual-calculation.md 手工算例。
 * 费率与样本：v1(至 2026-03-01)/v2(03-01~03-15)/v3(03-15 起)，阶梯一档 50kWh 不加价、二档加价。
 */
class BillingEngineTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Function<LocalDate, DayType> WORKDAY = d -> DayType.WORKDAY;
    private static final Duration GAP = Duration.ofSeconds(3 * 3600); // 抄表 1h/1.5h/2h × 1.5 阈值统一取 3h

    private Instant t(String local) {
        return LocalDateTime.parse(local).atZone(ZONE).toInstant();
    }

    private BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private Tariff tariff(String id, String code, String from, String to,
                          String sharp, String flat, String valley,
                          Integer tier1Upper, String tier2Surcharge) {
        return new Tariff(id, code, t(from), to == null ? null : t(to),
                List.of(
                        new TouRule(PeriodType.SHARP, 1200, 1320, bd(sharp)),
                        new TouRule(PeriodType.FLAT, 360, 1200, bd(flat)),
                        new TouRule(PeriodType.VALLEY, 1320, 360, bd(valley))
                ),
                List.of(
                        new TierBand(1, bd("0"), tier1Upper == null ? null : bd(tier1Upper.toString()), bd("0")),
                        new TierBand(2, bd(tier1Upper == null ? "1000" : tier1Upper.toString()), null, bd(tier2Surcharge))
                ));
    }

    private List<Tariff> tariffs() {
        return List.of(
                tariff("tv1", "V2025-STD", "2025-01-01T00:00:00", "2026-03-01T00:00:00",
                        "1.20", "0.70", "0.35", 1000, "0"),
                tariff("tv2", "V2026-03-PREMIUM", "2026-03-01T00:00:00", "2026-03-15T00:00:00",
                        "1.3025", "0.753", "0.40", 50, "0.10"),
                tariff("tv3", "V2026-03MID-ADJ", "2026-03-15T00:00:00", null,
                        "1.35", "0.78", "0.42", 50, "0.12")
        );
    }

    private RawReading r(String ts, String value) {
        return new RawReading(t(ts), bd(value));
    }

    @Test
    void m001_february_crossMidnightAndMonthBoundary_missingExcluded() {
        // 恒定 3kWh/h、2h 抄表、阈值 3h；漏抄 09:00 一点形成 4h 缺失（12kWh 排除）
        List<RawReading> readings = List.of(
                r("2026-02-27T21:00:00", "0"),
                r("2026-02-27T23:00:00", "6"),
                r("2026-02-28T01:00:00", "12"),
                r("2026-02-28T03:00:00", "18"),
                r("2026-02-28T05:00:00", "24"),
                r("2026-02-28T07:00:00", "30"),
                r("2026-02-28T11:00:00", "42"),
                r("2026-02-28T13:00:00", "48"),
                r("2026-02-28T15:00:00", "54"),
                r("2026-02-28T17:00:00", "60"),
                r("2026-02-28T19:00:00", "66"),
                r("2026-02-28T21:00:00", "72"),
                r("2026-02-28T23:00:00", "78"),
                r("2026-03-01T01:00:00", "84")
        );
        BillingEngine engine = new BillingEngine(ZONE, WORKDAY, GAP);
        Result res = engine.calculate(t("2026-02-01T00:00:00"), t("2026-03-01T00:00:00"),
                readings, tariffs());

        // 谷 30、平 30、尖 9（漏抄 09:00 形成 07:00→11:00 缺失，12kWh 排除）
        assertEquals(0, new BigDecimal("69").compareTo(res.totalKwh()));
        // 30*.35 + 30*.70 + 9*1.20 = 10.50 + 21.00 + 10.80 = 42.30
        assertMoney("42.30", res.totalAmount());
        assertMoney("0.00", res.roundingAmount());
        // 07:00→11:00 缺失 + 首条读数前 BEFORE_FIRST
        assertEquals(2, res.gaps().size());
        // 跨午夜且跨调价时刻的区间 23:00→01:00：在 2 月账期侧被精确切到 00:00（版本边界），
        // 片段属于 v1 谷段；区间另一侧（v2）归入 3 月账期，由 m001_march 用例验证。
        boolean splitAtBoundary = res.fragments().stream().flatMap(f -> f.segments().stream())
                .anyMatch(s -> s.segmentEnd().equals(t("2026-03-01T00:00:00"))
                        && s.tariffId().equals("tv1") && s.periodType() == PeriodType.VALLEY
                        && s.kwh().compareTo(new BigDecimal("3")) == 0);
        assertTrue(splitAtBoundary, "23:00→01:00 区间在 2 月侧应切成 [23:00,00:00) 的 3kWh v1 谷段");
    }

    @Test
    void m001_march_newPriceAcrossMidnight() {
        // 03-01 起 v2 新价；跨午夜区间 23:00(02-28)→01:00(03-01) 右侧 3kWh 按 v2 谷价
        List<RawReading> readings = List.of(
                r("2026-02-28T23:00:00", "78"),
                r("2026-03-01T01:00:00", "84"),
                r("2026-03-01T03:00:00", "90"),
                r("2026-03-01T05:00:00", "96")
        );
        BillingEngine engine = new BillingEngine(ZONE, WORKDAY, GAP);
        Result res = engine.calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"),
                readings, tariffs());

        // 谷 15（00-01 3 + 01-03 6 + 03-05 6，均为 v2 新价 0.40）；不足 50kWh 无阶梯
        assertEquals(0, new BigDecimal("15").compareTo(res.totalKwh()));
        assertMoney("6.00", res.totalAmount());
        assertMoney("0.00", res.roundingAmount());
        // 账期后无读数 AFTER_LAST
        assertEquals(1, res.gaps().size());
        assertEquals("AFTER_LAST", res.gaps().get(0).reason());
        // 同一读数区间（起于 02-28 23:00 v1）在 3 月侧的片段 [00:00,01:00) 归 v2 新价谷段
        boolean marchSide = res.fragments().stream().flatMap(f -> f.segments().stream())
                .anyMatch(s -> s.segmentStart().equals(t("2026-03-01T00:00:00"))
                        && s.tariffId().equals("tv2") && s.periodType() == PeriodType.VALLEY
                        && s.kwh().compareTo(new BigDecimal("3")) == 0);
        assertTrue(marchSide, "跨边界区间在 3 月侧应为 3kWh v2 谷段（证明版本切换两侧各按其价）");
    }

    /**
     * 舍入差额非零最小算例：两个时段单价均为 1.005、各 1kWh。
     * 未舍入 2.010 → 应收 2.01；分项各 HALF_UP 为 1.01，和 2.02；差额 -0.01 单列。
     */
    @Test
    void roundingDifferenceIsBookedSeparately() {
        Tariff only = new Tariff("x", "X", t("2026-03-01T00:00:00"), null,
                List.of(
                        new TouRule(PeriodType.SHARP, 0, 720, bd("1.005")),
                        new TouRule(PeriodType.FLAT, 720, 1440, bd("1.005"))
                ),
                List.of(new TierBand(1, bd("0"), null, bd("0"))));
        List<RawReading> readings = List.of(
                r("2026-03-01T00:00:00", "0"),
                r("2026-03-01T12:00:00", "1"),
                r("2026-03-02T00:00:00", "2")
        );
        Result res = new BillingEngine(ZONE, WORKDAY, Duration.ofHours(24))
                .calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"), readings, List.of(only));
        assertMoney("2.01", res.totalAmount());
        assertMoney("2.02", res.roundedLineSum());
        assertMoney("-0.01", res.roundingAmount());
    }

    @Test
    void m002_midMonthChangeAndExactTierBoundaryCross() {
        List<RawReading> readings = List.of(
                r("2026-03-14T00:00:00", "0"),
                r("2026-03-14T01:30:00", "3"),
                r("2026-03-14T03:00:00", "6"),
                r("2026-03-14T04:30:00", "9"),
                r("2026-03-14T06:00:00", "12"),
                r("2026-03-14T07:30:00", "15"),
                r("2026-03-14T09:00:00", "18"),
                r("2026-03-14T10:30:00", "21"),
                r("2026-03-14T12:00:00", "24"),
                r("2026-03-14T13:30:00", "27"),
                r("2026-03-14T15:00:00", "30"),
                r("2026-03-14T16:30:00", "33"),
                r("2026-03-14T18:00:00", "36"),
                r("2026-03-14T19:30:00", "39"),
                r("2026-03-14T21:00:00", "42"),
                r("2026-03-14T22:30:00", "45"),
                r("2026-03-15T00:00:00", "48"),
                r("2026-03-15T01:30:00", "51"),
                r("2026-03-15T03:00:00", "54")
        );
        BillingEngine engine = new BillingEngine(ZONE, WORKDAY, GAP);
        Result res = engine.calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"),
                readings, tariffs());

        // 恰好越过 50 边界：50 度一档、4 度二档（越界片段 3 拆成 2+1，再加上后续一整段 3）
        assertMoney("54.000000000", res.totalKwh());
        TierLine tier2 = res.tierLines().stream().filter(l -> l.tierIndex() == 2).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("4").compareTo(tier2.kwh()));
        assertEquals("tv3", tier2.tariffId());
        assertMoney("0.48", tier2.amount());

        // v2: 谷 16、尖 4、平 28；v3 谷 6
        EnergyLine v2Valley = line(res, "tv2", PeriodType.VALLEY);
        assertEquals(0, new BigDecimal("16").compareTo(v2Valley.kwh()));
        EnergyLine v2Sharp = line(res, "tv2", PeriodType.SHARP);
        assertEquals(0, new BigDecimal("4").compareTo(v2Sharp.kwh()));
        EnergyLine v2Flat = line(res, "tv2", PeriodType.FLAT);
        assertEquals(0, new BigDecimal("28").compareTo(v2Flat.kwh()));
        EnergyLine v3Valley = line(res, "tv3", PeriodType.VALLEY);
        assertEquals(0, new BigDecimal("6").compareTo(v3Valley.kwh()));
        // 能量费 16*.4+4*1.3025+28*.753+6*.42 = 6.4+5.21+21.084+2.52=35.214；+ 阶梯 0.48 = 35.694 -> 35.69
        assertMoney("35.69", res.totalAmount());
        assertMoney("0.00", res.roundingAmount());
    }

    @Test
    void missingReadingsAreNeverZero() {
        // 只有账期两端读数，中间存在超长间隔 -> 整月无计费电量，缺口显式登记
        List<RawReading> readings = List.of(
                r("2026-03-01T00:00:00", "100"),
                r("2026-03-20T00:00:00", "200")
        );
        Result res = new BillingEngine(ZONE, WORKDAY, GAP)
                .calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"), readings, tariffs());
        assertEquals(0, BigDecimal.ZERO.compareTo(res.totalKwh()));
        assertMoney("0.00", res.totalAmount());
        assertFalse(res.gaps().isEmpty());
        assertTrue(res.fragments().isEmpty(), "缺失区间不得产生任何计费片段");
    }

    @Test
    void reverseReadingFailsLoudly() {
        List<RawReading> readings = List.of(
                r("2026-03-01T00:00:00", "100"),
                r("2026-03-01T01:00:00", "99")
        );
        assertThrows(BillingException.class, () -> new BillingEngine(ZONE, WORKDAY, GAP)
                .calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"), readings, tariffs()));
    }

    @Test
    void tariffCoverageGapFailsLoudly() {
        Tariff shortTariff = new Tariff("a", "A", t("2026-03-01T00:00:00"), t("2026-03-10T00:00:00"),
                List.of(new TouRule(PeriodType.FLAT, 0, 1440, bd("1"))),
                List.of(new TierBand(1, bd("0"), null, bd("0"))));
        // 密集小时读数避开“缺失”分支，让引擎在 03-10 费率终止后立即因费率缺口报错
        List<RawReading> readings = new java.util.ArrayList<>();
        for (int d = 1; d <= 11; d++) {
            readings.add(r(String.format("2026-03-%02dT00:00:00", d), String.valueOf(d * 10)));
        }
        assertThrows(BillingException.class, () -> new BillingEngine(ZONE, WORKDAY, Duration.ofDays(60))
                .calculate(t("2026-03-01T00:00:00"), t("2026-04-01T00:00:00"), readings, List.of(shortTariff)));
    }

    private EnergyLine line(Result res, String tariff, PeriodType type) {
        return res.energyLines().stream()
                .filter(l -> l.tariffId().equals(tariff) && l.periodType() == type)
                .findFirst().orElseThrow();
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual.toPlainString());
    }
}
