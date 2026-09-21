package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** 构造按小时整点读数的测试数据；读数为累计表底，kWh 增量由各小时类型决定。 */
final class TestFixtures {

    static final ZoneId TZ = ZoneId.of("Asia/Shanghai");

    private TestFixtures() {}

    /** 按每天各时段小时数生成整月整点读数：
     *  谷 22-06(8h)、平 6-8/12-13/21-22(4h)、峰 8-10/15-21(8h)、尖 10-12/13-15(4h)。 */
    static List<ReadingInterval> hourlyMonth(int v, int f, int p, int s) {
        return hourlyMonth(v, f, p, s,
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 4, 1));
    }

    static List<ReadingInterval> hourlyMonth(int v, int f, int p, int s,
                                             LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(TZ).toInstant();
        Instant end = to.atStartOfDay(TZ).toInstant();
        List<Instant> times = new ArrayList<>();
        for (Instant t = start; !t.isAfter(end); t = t.plusSeconds(3600)) {
            times.add(t);
        }
        List<ReadingInterval> out = new ArrayList<>();
        BigDecimal reading = BigDecimal.ZERO;
        for (int i = 1; i < times.size(); i++) {
            Instant a = times.get(i - 1);
            Instant b = times.get(i);
            int type = hourType(a); // [a,b) 的时段类型由起点小时决定
            int kwh = switch (type) {
                case 0 -> v; // 谷
                case 1 -> f; // 平
                case 2 -> p; // 峰
                default -> s; // 尖
            };
            reading = reading.add(BigDecimal.valueOf(kwh));
            out.add(new ReadingInterval(a, b, reading.subtract(BigDecimal.valueOf(kwh)),
                    reading, BigDecimal.valueOf(kwh)));
        }
        return out;
    }

    /** 返回区间起点小时的时段类型：0谷 1平 2峰 3尖（用于 [a,b) 小时区间）。 */
    static int hourType(Instant start) {
        int h = start.atZone(TZ).getHour();
        if (h >= 22 || h < 6) {
            return 0;
        }
        if (h < 8 || h == 12 || h == 21) {
            return 1;
        }
        if ((h >= 8 && h < 10) || (h >= 15 && h < 21)) {
            return 2;
        }
        return 3; // 10-12, 13-15
    }

    static List<TouRule> touRules(String sharp, String peak, String flat, String valley) {
        int all = 0b1111111;
        return List.of(
                new TouRule(PeriodType.SHARP, all, 600, 720, new BigDecimal(sharp)),
                new TouRule(PeriodType.SHARP, all, 780, 900, new BigDecimal(sharp)),
                new TouRule(PeriodType.PEAK, all, 480, 600, new BigDecimal(peak)),
                new TouRule(PeriodType.PEAK, all, 900, 1260, new BigDecimal(peak)),
                new TouRule(PeriodType.FLAT, all, 360, 480, new BigDecimal(flat)),
                new TouRule(PeriodType.FLAT, all, 720, 780, new BigDecimal(flat)),
                new TouRule(PeriodType.FLAT, all, 1260, 1320, new BigDecimal(flat)),
                new TouRule(PeriodType.VALLEY, all, 1320, 360, new BigDecimal(valley)));
    }

    static RateVersionInfo version(long id, int no, Instant from, Instant to,
                                   String sharp, String peak, String flat, String valley) {
        return new RateVersionInfo(id, "TOU", no, from, to, true, null,
                touRules(sharp, peak, flat, valley));
    }

    static RateVersionInfo flat(long id, int no, Instant from, Instant to, String price) {
        return new RateVersionInfo(id, "FLAT", no, from, to, false, new BigDecimal(price), List.of());
    }

    static TierScheduleInfo surcharge() {
        return new TierScheduleInfo(1, "TS", 1, LocalDate.of(2026, 1, 1), null,
                TierScheduleInfo.PricingMode.SURCHARGE, List.of(
                        new TierScheduleInfo.Step(1, bd("0"), bd("200"), bd("0.10")),
                        new TierScheduleInfo.Step(2, bd("200"), bd("400"), bd("0.20")),
                        new TierScheduleInfo.Step(3, bd("400"), null, bd("0.30"))));
    }

    static TierScheduleInfo replaceTier() {
        return new TierScheduleInfo(2, "TR", 1, LocalDate.of(2026, 1, 1), null,
                TierScheduleInfo.PricingMode.REPLACE, List.of(
                        new TierScheduleInfo.Step(1, bd("0"), bd("500"), bd("0.80")),
                        new TierScheduleInfo.Step(2, bd("500"), null, bd("1.00"))));
    }

    static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
