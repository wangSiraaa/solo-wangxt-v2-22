package com.park.energy.billing;

import com.park.energy.billing.BillingModels.*;
import com.park.energy.domain.DayType;
import com.park.energy.domain.PeriodType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/**
 * 电费试算核心。纯十进制定点：所有电量 9 位、原始金额 12 位、应收金额 2 位，舍入一律 HALF_UP。
 *
 * 口径：
 * 1. 读数为单调不减的累计表底数；相邻读数间隔超过正常抄表节奏 => 缺失，整段不计费、不当零、登记缺口。
 * 2. 账期 [windowStart, windowEnd) 半开；区间按“账期边界 / 费率版本生效边界 / 本地午夜 / 时段边界”切分，
 *    片段电量 = 区间电量 × 片段时长占整段时长比例。
 * 3. 尖峰平谷按固定时区本地墙钟匹配，允许跨午夜规则（startMin &gt; endMin）。
 * 4. 月度阶梯：电量按时间顺序进入“当时生效版本”的档位，月累计电量全局连续；版本切换后档位表随新版本。
 * 5. 每个费用项先保留未舍入金额，按项舍入到分；舍入差额单列一项，应收 = 未舍入合计舍入到分。
 */
public class BillingEngine {

    public static final int KWH_SCALE = 9;
    public static final int RAW_SCALE = 12;
    public static final int MONEY_SCALE = 2;

    private final ZoneId zone;
    private final Function<LocalDate, DayType> dayTypeResolver;
    private final Duration maxExpectedGap;

    public BillingEngine(ZoneId zone, Function<LocalDate, DayType> dayTypeResolver, Duration maxExpectedGap) {
        this.zone = Objects.requireNonNull(zone);
        this.dayTypeResolver = Objects.requireNonNull(dayTypeResolver);
        this.maxExpectedGap = Objects.requireNonNull(maxExpectedGap);
    }

    public Result calculate(Instant windowStart, Instant windowEnd,
                            List<RawReading> readings, List<Tariff> tariffs) {
        Objects.requireNonNull(windowStart);
        Objects.requireNonNull(windowEnd);
        if (!windowEnd.isAfter(windowStart)) {
            throw new BillingException("账期窗口非法：结束必须晚于开始");
        }
        List<RawReading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(RawReading::ts));
        validateReadings(sorted);
        tariffs.forEach(this::validateTouCoverage);

        List<Gap> gaps = new ArrayList<>();
        List<Fragment> fragments = new ArrayList<>();

        if (sorted.isEmpty()) {
            gaps.add(new Gap(windowStart, windowEnd, "BEFORE_FIRST", "账期内无任何读数，整月缺失，不计费"));
            return emptyResult(windowStart, windowEnd, gaps);
        }

        Instant firstTs = sorted.get(0).ts();
        Instant lastTs = sorted.get(sorted.size() - 1).ts();
        if (firstTs.isAfter(windowStart)) {
            gaps.add(new Gap(windowStart, min(firstTs, windowEnd), "BEFORE_FIRST",
                    "第一条读数晚于账期起点，此前无表码，不计费"));
        }
        if (lastTs.isBefore(windowEnd)) {
            gaps.add(new Gap(max(lastTs, windowStart), windowEnd, "AFTER_LAST",
                    "最后一条读数早于账期终点，此后无表码，不计费"));
        }

        // 相邻有效点配对；账期边界处由调用方额外提供账期前最后一条/账期后第一条读数。
        // 缺口判断基于整对点间隔；计费区间裁剪到账期内。
        for (int i = 0; i + 1 < sorted.size(); i++) {
            RawReading a = sorted.get(i);
            RawReading b = sorted.get(i + 1);

            Instant clipStart = max(a.ts(), windowStart);
            Instant clipEnd = min(b.ts(), windowEnd);
            if (!clipEnd.isAfter(clipStart)) {
                continue; // 与账期无交集
            }

            Duration pairDur = Duration.between(a.ts(), b.ts());
            if (pairDur.compareTo(maxExpectedGap) > 0) {
                gaps.add(new Gap(clipStart, clipEnd, "MISSING_READING",
                        "相邻读数间隔 " + pairDur.toSeconds() + "s 超过允许上限 "
                                + maxExpectedGap.toSeconds() + "s，按缺失处理，不计费、不当零"));
                continue;
            }

            BigDecimal intervalKwh = b.readingKwh().subtract(a.readingKwh())
                    .setScale(KWH_SCALE, RoundingMode.HALF_UP);
            List<Segment> segments = splitInterval(clipStart, clipEnd, a, b, intervalKwh, pairDur, tariffs);
            fragments.add(new Fragment(a.ts(), b.ts(), a.readingKwh(), b.readingKwh(), intervalKwh, segments));
        }

        if (fragments.isEmpty()) {
            return emptyResult(windowStart, windowEnd, gaps);
        }

        List<EnergyLine> energyLines = buildEnergyLines(fragments);
        List<TierAllocation> allocations = allocateTiers(fragments, tariffs);
        List<TierLine> tierLines = buildTierLines(allocations);

        BigDecimal totalKwh = sumFragments(fragments);
        BigDecimal rawEnergy = energyLines.stream().map(EnergyLine::rawAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rawTier = tierLines.stream().map(TierLine::rawAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rawTotal = rawEnergy.add(rawTier).setScale(RAW_SCALE, RoundingMode.HALF_UP);
        BigDecimal roundedLineSum = StreamConcat.lines(energyLines, tierLines);
        BigDecimal totalAmount = rawTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal roundingAmount = totalAmount.subtract(roundedLineSum).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        return new Result(windowStart, windowEnd, fragments, energyLines, tierLines,
                allocations, gaps, totalKwh, rawTotal, roundedLineSum, roundingAmount, totalAmount, false);
    }

    // ----------------------------- 区间切分 -----------------------------

    private List<Segment> splitInterval(Instant clipStart, Instant clipEnd,
                                        RawReading a, RawReading b, BigDecimal intervalKwh,
                                        Duration pairDur, List<Tariff> tariffs) {
        List<Segment> out = new ArrayList<>();
        // 第一层：费率版本生效边界
        TreeSet<Instant> tariffCuts = new TreeSet<>();
        for (Tariff t : tariffs) {
            if (t.effectiveFrom().isAfter(clipStart) && t.effectiveFrom().isBefore(clipEnd)) {
                tariffCuts.add(t.effectiveFrom());
            }
            Instant to = t.effectiveTo();
            if (to != null && to.isAfter(clipStart) && to.isBefore(clipEnd)) {
                tariffCuts.add(to);
            }
        }
        List<Instant> tBreaks = new ArrayList<>();
        tBreaks.add(clipStart);
        tBreaks.addAll(tariffCuts);
        tBreaks.add(clipEnd);

        long totalSeconds = pairDur.getSeconds();
        for (int i = 0; i + 1 < tBreaks.size(); i++) {
            Instant vs = tBreaks.get(i);
            Instant ve = tBreaks.get(i + 1);
            if (!ve.isAfter(vs)) {
                continue;
            }
            Tariff tariff = tariffAt(tariffs, vs);
            if (tariff == null) {
                throw new BillingException("费率版本未覆盖时刻 " + vs + "，无法计费（请补齐生效区间）");
            }
            TreeSet<Instant> microCuts = new TreeSet<>();
            microCuts.add(vs);
            // 本地午夜无条件作为切点：跨午夜时段（如谷 22:00-06:00）在 00:00 两侧
            // 虽属同一时段类型，但必须切开，否则整段中点可能落在错误的日历日/时段。
            microCuts.addAll(midnightsBetween(vs, ve.plusSeconds(1)));
            microCuts.addAll(touBoundaryInstants(tariff, vs, ve.plusSeconds(1)));
            microCuts.add(ve);

            List<Instant> mBreaks = new ArrayList<>(microCuts);
            for (int j = 0; j + 1 < mBreaks.size(); j++) {
                Instant ms = mBreaks.get(j);
                Instant me = mBreaks.get(j + 1);
                if (!me.isAfter(ms)) {
                    continue;
                }
                Instant mid = ms.plus(Duration.between(ms, me).dividedBy(2));
                ZonedDateTime mz = mid.atZone(zone);
                int minute = mz.getHour() * 60 + mz.getMinute();
                TouRule rule = matchTou(tariff, minute);
                if (rule == null) {
                    throw new BillingException("费率 " + tariff.code() + " 在 " + mz + " 无匹配时段");
                }
                DayType dayType = dayTypeResolver.apply(mz.toLocalDate());
                long segSeconds = Duration.between(ms, me).getSeconds();
                BigDecimal share = BigDecimal.valueOf(segSeconds)
                        .divide(BigDecimal.valueOf(totalSeconds), RAW_SCALE, RoundingMode.HALF_UP);
                BigDecimal kwh = intervalKwh.multiply(share).setScale(KWH_SCALE, RoundingMode.HALF_UP);
                out.add(new Segment(ms, me, share, kwh, rule.periodType(), dayType,
                        tariff.id(), rule.pricePerKwh()));
            }
        }
        return out;
    }

    private Set<Instant> midnightsBetween(Instant s, Instant e) {
        TreeSet<Instant> set = new TreeSet<>();
        LocalDate d0 = s.atZone(zone).toLocalDate();
        LocalDate d1 = e.atZone(zone).toLocalDate();
        for (LocalDate d = d0.plusDays(1); !d.isAfter(d1); d = d.plusDays(1)) {
            Instant inst = d.atStartOfDay(zone).toInstant();
            if (inst.isAfter(s) && inst.isBefore(e)) {
                set.add(inst);
            }
        }
        return set;
    }

    /**
     * 生成 [s,e) 内该版本全部时段边界时刻。跨午夜规则 start&gt;end 时：
     * 起点落在本地日期 D 的 startMin，终点落在 D+1 的 endMin；因此扫描区间前后各扩一天。
     */
    private Set<Instant> touBoundaryInstants(Tariff tariff, Instant s, Instant e) {
        TreeSet<Instant> set = new TreeSet<>();
        LocalDate d0 = s.atZone(zone).toLocalDate().minusDays(1);
        LocalDate d1 = e.atZone(zone).toLocalDate().plusDays(1);
        for (LocalDate d = d0; !d.isAfter(d1); d = d.plusDays(1)) {
            for (TouRule rule : tariff.touRules()) {
                Instant startInst = localInstant(d, rule.startMin());
                if (startInst.isAfter(s) && startInst.isBefore(e)) {
                    set.add(startInst);
                }
                LocalDate endDate = rule.startMin() > rule.endMin() ? d.plusDays(1) : d;
                Instant endInst = localInstant(endDate, rule.endMin());
                if (endInst.isAfter(s) && endInst.isBefore(e)) {
                    set.add(endInst);
                }
            }
        }
        return set;
    }

    private Instant localInstant(LocalDate date, int minute) {
        if (minute == 1440) {
            return date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        return date.atTime(LocalTime.of(minute / 60, minute % 60)).atZone(zone).toInstant();
    }

    private TouRule matchTou(Tariff tariff, int minute) {
        for (TouRule r : tariff.touRules()) {
            if (r.startMin() > r.endMin()) {
                if (minute >= r.startMin() || minute < r.endMin()) {
                    return r;
                }
            } else if (minute >= r.startMin() && minute < r.endMin()) {
                return r;
            }
        }
        return null;
    }

    /** 逐分钟校验同一费率版本内时段必须恰好覆盖全天一次（无缺口、无重叠）。 */
    private void validateTouCoverage(Tariff tariff) {
        String[] cover = new String[1440];
        for (TouRule r : tariff.touRules()) {
            if (r.startMin() == r.endMin()) {
                throw new BillingException("费率 " + tariff.code() + " 存在零长度时段");
            }
            if (r.startMin() < r.endMin()) {
                // 普通同日时段 [start,end)
                for (int m = r.startMin(); m < r.endMin(); m++) {
                    mark(cover, tariff, r, m);
                }
            } else {
                // 跨午夜时段 [start,1440) ∪ [0,end)
                for (int m = r.startMin(); m < 1440; m++) {
                    mark(cover, tariff, r, m);
                }
                for (int m = 0; m < r.endMin(); m++) {
                    mark(cover, tariff, r, m);
                }
            }
        }
        for (int m = 0; m < 1440; m++) {
            if (cover[m] == null) {
                throw new BillingException("费率 " + tariff.code() + " 时段定义未覆盖全天（第 " + m + " 分钟缺口）");
            }
        }
    }

    private void mark(String[] cover, Tariff tariff, TouRule r, int minute) {
        if (cover[minute] != null) {
            throw new BillingException("费率 " + tariff.code() + " 时段在第 " + minute + " 分钟重叠");
        }
        cover[minute] = r.periodType().name();
    }

    // ----------------------------- 阶梯分摊 -----------------------------

    private List<TierAllocation> allocateTiers(List<Fragment> fragments, List<Tariff> tariffs) {
        List<TierAllocation> allocs = new ArrayList<>();
        BigDecimal cumulative = BigDecimal.ZERO.setScale(KWH_SCALE, RoundingMode.HALF_UP);

        List<int[]> order = chronologicalSegmentOrder(fragments);
        for (int[] pos : order) {
            Fragment f = fragments.get(pos[0]);
            Segment seg = f.segments().get(pos[1]);
            Tariff tariff = requireTariff(tariffs, seg.tariffId());
            List<TierBand> bands = tariff.tiers().stream()
                    .sorted(Comparator.comparingInt(TierBand::tierIndex)).toList();
            if (bands.isEmpty()) {
                throw new BillingException("费率 " + tariff.code() + " 未配置月度阶梯");
            }
            BigDecimal remaining = seg.kwh();
            while (remaining.compareTo(BigDecimal.ZERO) > 0) {
                TierBand band = bandAt(bands, cumulative);
                BigDecimal before = cumulative;
                BigDecimal available;
                if (band.upperKwh() == null) {
                    available = remaining;
                } else {
                    available = band.upperKwh().subtract(cumulative);
                }
                if (available.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BillingException("阶梯档位边界配置错误 @ " + cumulative);
                }
                BigDecimal take = remaining.min(available).setScale(KWH_SCALE, RoundingMode.HALF_UP);
                allocs.add(new TierAllocation(band.tierIndex(), tariff.id(), pos[1], pos[0],
                        take, before, band.surchargePerKwh()));
                cumulative = cumulative.add(take);
                remaining = remaining.subtract(take);
            }
        }
        return allocs;
    }

    private TierBand bandAt(List<TierBand> bands, BigDecimal cumulative) {
        for (TierBand b : bands) {
            boolean aboveLower = cumulative.compareTo(b.lowerKwh()) >= 0;
            boolean belowUpper = b.upperKwh() == null || cumulative.compareTo(b.upperKwh()) < 0;
            if (aboveLower && belowUpper) {
                return b;
            }
        }
        throw new BillingException("阶梯未覆盖累计电量 " + cumulative);
    }

    private List<int[]> chronologicalSegmentOrder(List<Fragment> fragments) {
        List<int[]> order = new ArrayList<>();
        for (int fi = 0; fi < fragments.size(); fi++) {
            List<Segment> segs = fragments.get(fi).segments();
            for (int si = 0; si < segs.size(); si++) {
                order.add(new int[]{fi, si});
            }
        }
        order.sort(Comparator.comparing(p -> fragments.get(p[0]).segments().get(p[1]).segmentStart()));
        return order;
    }

    // ----------------------------- 汇总 -----------------------------

    private List<EnergyLine> buildEnergyLines(List<Fragment> fragments) {
        record Key(String tariffId, PeriodType type) {}
        // 按 (费率版本, 尖峰平谷) 聚合；费率版本时间顺序与片段出现顺序一致，LinkedHashMap 保证行序稳定
        Map<Key, BigDecimal[]> map = new LinkedHashMap<>();
        for (Fragment f : fragments) {
            for (Segment s : f.segments()) {
                Key key = new Key(s.tariffId(), s.periodType());
                BigDecimal[] v = map.computeIfAbsent(key,
                        k -> new BigDecimal[]{BigDecimal.ZERO.setScale(KWH_SCALE, RoundingMode.HALF_UP), BigDecimal.ZERO});
                v[0] = v[0].add(s.kwh());
                v[1] = v[1].add(s.kwh().multiply(s.pricePerKwh()));
            }
        }
        List<EnergyLine> lines = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal[]> e : map.entrySet()) {
            BigDecimal kwh = e.getValue()[0].setScale(KWH_SCALE, RoundingMode.HALF_UP);
            BigDecimal raw = e.getValue()[1].setScale(RAW_SCALE, RoundingMode.HALF_UP);
            lines.add(new EnergyLine(e.getKey().tariffId(), e.getKey().type(), kwh, raw,
                    raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }
        return lines;
    }

    private List<TierLine> buildTierLines(List<TierAllocation> allocations) {
        record Key(String tariffId, int tierIndex) {}
        Map<Key, BigDecimal[]> map = new LinkedHashMap<>();
        for (TierAllocation a : allocations) {
            Key key = new Key(a.tariffId(), a.tierIndex());
            BigDecimal[] v = map.computeIfAbsent(key,
                    k -> new BigDecimal[]{BigDecimal.ZERO.setScale(KWH_SCALE, RoundingMode.HALF_UP), BigDecimal.ZERO});
            v[0] = v[0].add(a.kwh());
            v[1] = v[1].add(a.kwh().multiply(a.surchargePerKwh()));
        }
        List<TierLine> lines = new ArrayList<>();
        for (Map.Entry<Key, BigDecimal[]> e : map.entrySet()) {
            BigDecimal raw = e.getValue()[1].setScale(RAW_SCALE, RoundingMode.HALF_UP);
            lines.add(new TierLine(e.getKey().tariffId(), e.getKey().tierIndex(),
                    e.getValue()[0], raw, raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP)));
        }
        return lines;
    }

    private BigDecimal sumFragments(List<Fragment> fragments) {
        BigDecimal sum = BigDecimal.ZERO.setScale(KWH_SCALE, RoundingMode.HALF_UP);
        for (Fragment f : fragments) {
            for (Segment s : f.segments()) {
                sum = sum.add(s.kwh());
            }
        }
        return sum;
    }

    // ----------------------------- 其它 -----------------------------

    private void validateReadings(List<RawReading> sorted) {
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).ts().equals(sorted.get(i - 1).ts())) {
                throw new BillingException("存在重复时刻读数：" + sorted.get(i).ts());
            }
            if (sorted.get(i).readingKwh().compareTo(sorted.get(i - 1).readingKwh()) < 0) {
                throw new BillingException("表码倒走：" + sorted.get(i - 1).ts() + " -> " + sorted.get(i).ts()
                        + " (" + sorted.get(i - 1).readingKwh() + " -> " + sorted.get(i).readingKwh() + ")");
            }
        }
    }

    private Tariff tariffAt(List<Tariff> tariffs, Instant t) {
        for (Tariff tariff : tariffs) {
            boolean after = !t.isBefore(tariff.effectiveFrom());
            boolean before = tariff.effectiveTo() == null || t.isBefore(tariff.effectiveTo());
            if (after && before) {
                return tariff;
            }
        }
        return null;
    }

    private Tariff requireTariff(List<Tariff> tariffs, String id) {
        return tariffs.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new BillingException("内部错误：费率版本 " + id + " 丢失"));
    }

    private Result emptyResult(Instant s, Instant e, List<Gap> gaps) {
        BigDecimal zero2 = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new Result(s, e, List.of(), List.of(), List.of(), List.of(), gaps,
                BigDecimal.ZERO.setScale(KWH_SCALE, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(RAW_SCALE, RoundingMode.HALF_UP), zero2, zero2, zero2, true);
    }

    private static Instant min(Instant x, Instant y) { return x.isBefore(y) ? x : y; }
    private static Instant max(Instant x, Instant y) { return x.isAfter(y) ? x : y; }

    /** 占位辅助：删除早期未用聚合分支（保留清晰的 LinkedHashMap 版本）。 */
    private static final class StreamConcat {
        static BigDecimal lines(List<EnergyLine> e, List<TierLine> t) {
            BigDecimal sum = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            for (EnergyLine l : e) {
                sum = sum.add(l.amount());
            }
            for (TierLine l : t) {
                sum = sum.add(l.amount());
            }
            return sum;
        }
    }
}
