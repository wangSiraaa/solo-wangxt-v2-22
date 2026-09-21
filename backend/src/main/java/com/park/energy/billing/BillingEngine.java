package com.park.energy.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计费引擎（无 Spring 依赖、无 IO，可独立单测）。
 *
 * 切分口径：读数列 × 费率版本边界 × 账月边界取并集，再按「本地日 TOU 窗口」细分；
 * 小区间内匀速用电，电量按分钟线性分摊（同区间分摊残差补给最后一段，保证分毫不差）。
 */
public class BillingEngine {

    public static final int SCALE_KWH = 4;
    public static final int SCALE_AMOUNT = 4;
    public static final int SCALE_MONEY = 2;
    public static final int SCALE_RATIO = 10;
    public static final int SCALE_PRICE = 6;

    public record LineSpec(
            String kind,                 // TOU / TIER
            String label,
            PeriodType periodType,       // 可为 null
            Integer tierStepNo,          // 可为 null
            BigDecimal kwh,
            BigDecimal unitPrice,        // 多版本混合时 null，单价见 fragments
            BigDecimal amountRaw,        // 4 位小数
            BigDecimal amount,           // 2 位小数（HALF_UP）
            List<ChargedFragment> fragments
    ) {}

    public record BillCalc(
            BigDecimal totalKwh,
            List<LineSpec> lines,
            BigDecimal rawToBillRounding,   // 账单总额 4位→2位 的舍入差额
            BigDecimal balancingRounding,   // 行级+账单级合计的平衡项（分）
            BigDecimal totalAmount
    ) {}

    /** 费率版本边界切出的物理片（尚未按 TOU 细分）。 */
    private record Slice(
            Instant start, Instant end,
            BigDecimal startReading, BigDecimal endReading, BigDecimal fullKwh,
            long fullIntervalMinutes,
            RateVersionInfo version,
            boolean clipped,
            BigDecimal allocatedKwh, BigDecimal allocateRatio
    ) {}

    private record DayWindow(int startMin, int endMin, PeriodType type, BigDecimal price) {}

    public BillCalc calculate(List<ReadingInterval> intervals,
                              LocalDate billMonth,
                              ZoneId tz,
                              List<RateVersionInfo> rateVersions,
                              TierScheduleInfo tier) {
        Instant monthStart = billMonth.atStartOfDay(tz).toInstant();
        Instant monthEnd = billMonth.plusMonths(1).atStartOfDay(tz).toInstant();

        // 1) 校验费率版本对整月连续覆盖，不允许空档（空档无法定价）
        validateRateCoverage(rateVersions, monthStart, monthEnd);

        // 2) 读数区间 × 版本边界 × 账月边界 → 物理片，并匀速分摊电量
        List<Slice> slices = buildSlices(intervals, monthStart, monthEnd, rateVersions);

        // 3) 物理片按本地日 TOU 窗口细分（非 TOU 版本直接单一价）
        List<ChargedFragment> energyFragments = new ArrayList<>();
        for (Slice sl : slices) {
            energyFragments.addAll(classify(sl, tz));
        }

        List<LineSpec> lines = new ArrayList<>();

        // 4) 能源费行
        boolean replaceTier = tier != null && tier.mode() == TierScheduleInfo.PricingMode.REPLACE;
        if (!replaceTier) {
            lines.addAll(buildTouLines(energyFragments));
        }

        // 5) 阶梯行
        if (tier != null) {
            List<ChargedFragment> tierSource = replaceTier
                    ? slicesToFragments(slices) : energyFragments;
            lines.addAll(buildTierLines(tierSource, tier, replaceTier));
        }

        // 6) 合计、逐行舍入与舍入差额
        BigDecimal totalKwh = energyOrSliceTotal(slices, replaceTier);
        BigDecimal sumRaw = lines.stream().map(LineSpec::amountRaw).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<LineSpec> normalized = new ArrayList<>(lines);
        BigDecimal sumLineRounded = normalized.stream().map(LineSpec::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAmount = sumRaw.setScale(SCALE_MONEY, RoundingMode.HALF_UP);
        BigDecimal rawToBill = totalAmount.subtract(sumRaw);                       // 4 位
        BigDecimal balancing = totalAmount.subtract(sumLineRounded);               // 2 位（分）
        if (balancing.compareTo(BigDecimal.ZERO) != 0) {
            normalized.add(new LineSpec("ROUNDING", "舍入差额", null, null,
                    BigDecimal.ZERO.setScale(SCALE_KWH), null,
                    balancing.setScale(SCALE_AMOUNT), balancing.setScale(SCALE_MONEY), List.of()));
        }
        return new BillCalc(totalKwh.setScale(SCALE_KWH, RoundingMode.HALF_UP),
                normalized, rawToBill.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP),
                balancing.setScale(SCALE_MONEY, RoundingMode.HALF_UP), totalAmount);
    }

    private BigDecimal energyOrSliceTotal(List<Slice> slices, boolean replaceTier) {
        return slices.stream().map(Slice::allocatedKwh).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------
    // 费率覆盖校验
    // ------------------------------------------------------------------

    private void validateRateCoverage(List<RateVersionInfo> versions, Instant from, Instant to) {
        if (versions.isEmpty()) {
            throw new BillingRuleException("账月内没有任何有效费率版本，无法计费");
        }
        List<RateVersionInfo> sorted = versions.stream()
                .sorted(Comparator.comparing(RateVersionInfo::effectiveFrom)).toList();
        Instant cursor = from;
        for (RateVersionInfo v : sorted) {
            Instant vFrom = v.effectiveFrom().isBefore(from) ? from : v.effectiveFrom();
            Instant vTo = v.effectiveTo() == null || v.effectiveTo().isAfter(to) ? to : v.effectiveTo();
            if (!vFrom.isBefore(to) || !vTo.isAfter(from)) {
                continue; // 与本月不相交
            }
            if (vFrom.isAfter(cursor)) {
                throw new BillingRuleException("费率空档：" + cursor + " ~ " + vFrom + " 没有适用费率版本");
            }
            if (vTo.compareTo(cursor) <= 0) {
                throw new BillingRuleException("费率版本重叠：版本#" + v.versionNo());
            }
            cursor = vTo;
            if (!cursor.isBefore(to)) {
                return;
            }
        }
        if (cursor.isBefore(to)) {
            throw new BillingRuleException("费率空档：" + cursor + " ~ " + to + " 没有适用费率版本");
        }
    }

    private RateVersionInfo versionAt(List<RateVersionInfo> versions, Instant t) {
        return versions.stream().filter(v -> v.contains(t)).findFirst()
                .orElseThrow(() -> new BillingRuleException("时刻 " + t + " 无适用费率版本"));
    }

    // ------------------------------------------------------------------
    // 物理片切分 + 匀速分摊
    // ------------------------------------------------------------------

    private List<Slice> buildSlices(List<ReadingInterval> intervals, Instant monthStart,
                                    Instant monthEnd, List<RateVersionInfo> versions) {
        List<Slice> all = new ArrayList<>();
        for (ReadingInterval iv : intervals) {
            Instant a = iv.start().isBefore(monthStart) ? monthStart : iv.start();
            Instant b = iv.end().isAfter(monthEnd) ? monthEnd : iv.end();
            if (!a.isBefore(b) && !a.equals(b)) {
                continue;
            }
            if (a.equals(b)) {
                continue; // 零时长（读数恰好在边界）不计
            }
            boolean clipped = a.isAfter(iv.start()) || b.isBefore(iv.end());

            // 版本边界切点
            List<Instant> cuts = new ArrayList<>();
            cuts.add(a);
            for (RateVersionInfo v : versions) {
                if (v.effectiveFrom().isAfter(a) && v.effectiveFrom().isBefore(b)) {
                    cuts.add(v.effectiveFrom());
                }
                if (v.effectiveTo() != null && v.effectiveTo().isAfter(a) && v.effectiveTo().isBefore(b)) {
                    cuts.add(v.effectiveTo());
                }
            }
            cuts.add(b);
            List<Instant> bounds = cuts.stream().distinct().sorted().toList();

            long totalMin = Duration.between(iv.start(), iv.end()).toMinutes();
            long inMonthMin = Duration.between(a, b).toMinutes();
            if (inMonthMin <= 0) {
                continue;
            }
            // 匀速：账月内部分摊目标电量（4 位），片间残差补给最后一片
            BigDecimal targetKwh = clipped
                    ? iv.kwh().multiply(BigDecimal.valueOf(inMonthMin))
                            .divide(BigDecimal.valueOf(totalMin), SCALE_KWH, RoundingMode.HALF_UP)
                    : iv.kwh();

            List<Slice> raw = new ArrayList<>();
            for (int i = 0; i < bounds.size() - 1; i++) {
                Instant s = bounds.get(i);
                Instant e = bounds.get(i + 1);
                long m = Duration.between(s, e).toMinutes();
                if (m <= 0) {
                    continue;
                }
                RateVersionInfo v = versionAt(versions, s.plusSeconds(1));
                BigDecimal ratio = BigDecimal.valueOf(m)
                        .divide(BigDecimal.valueOf(totalMin), SCALE_RATIO, RoundingMode.HALF_UP);
                raw.add(new Slice(s, e, iv.startReading(), iv.endReading(), iv.kwh(),
                        totalMin, v, clipped, BigDecimal.ZERO, ratio));
            }
            distributeKwh(raw, targetKwh, inMonthMin);
            all.addAll(raw);
        }
        all.sort(Comparator.comparing(Slice::start));
        return all;
    }

    /** 按分钟比例分摊目标电量；四舍五入残差全部补给最后一片，保证 Σ 分摊 = 目标值。 */
    private void distributeKwh(List<Slice> raw, BigDecimal targetKwh, long inMonthMin) {
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < raw.size(); i++) {
            Slice sl = raw.get(i);
            BigDecimal kwh;
            if (i == raw.size() - 1) {
                kwh = targetKwh.subtract(allocated); // 残差兜底
            } else {
                kwh = targetKwh.multiply(BigDecimal.valueOf(Duration.between(sl.start(), sl.end()).toMinutes()))
                        .divide(BigDecimal.valueOf(inMonthMin), SCALE_KWH, RoundingMode.HALF_UP);
                allocated = allocated.add(kwh);
            }
            if (kwh.signum() < 0) {
                kwh = BigDecimal.ZERO.setScale(SCALE_KWH);
            }
            raw.set(i, new Slice(sl.start(), sl.end(), sl.startReading(), sl.endReading(), sl.fullKwh(),
                    sl.fullIntervalMinutes(), sl.version(), sl.clipped(),
                    kwh.setScale(SCALE_KWH, RoundingMode.HALF_UP), sl.allocateRatio()));
        }
    }

    // ------------------------------------------------------------------
    // TOU 细分
    // ------------------------------------------------------------------

    private List<ChargedFragment> classify(Slice sl, ZoneId tz) {
        RateVersionInfo v = sl.version();
        if (!v.tou()) {
            BigDecimal price = v.flatPrice();
            if (price == null) {
                throw new BillingRuleException("非 TOU 版本#" + v.versionNo() + " 缺少单一单价");
            }
            return List.of(new ChargedFragment(sl.start(), sl.end(), sl.startReading(), sl.endReading(),
                    sl.fullKwh(), Duration.between(sl.start(), sl.end()).toMinutes(),
                    sl.allocatedKwh(), sl.allocateRatio(), PeriodType.FLAT, v.id(),
                    price.setScale(SCALE_PRICE, RoundingMode.HALF_UP), null, sl.clipped()));
        }

        // 收集片覆盖到的所有本地日 TOU 窗口
        List<WindowHit> hits = new ArrayList<>();
        ZonedDateTime cursor = sl.start().atZone(tz);
        ZonedDateTime end = sl.end().atZone(tz);
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.toLocalDate();
            List<DayWindow> windows = daySchedule(v, day);
            int curMin = cursor.getHour() * 60 + cursor.getMinute();
            int stopMin = end.toLocalDate().equals(day)
                    ? end.getHour() * 60 + end.getMinute() : 1440;
            for (DayWindow w : windows) {
                if (w.endMin() <= curMin) {
                    continue;
                }
                if (w.startMin() >= stopMin) {
                    break;
                }
                int ws = Math.max(w.startMin(), curMin);
                int we = Math.min(w.endMin(), stopMin);
                if (we > ws) {
                    Instant fs = day.atStartOfDay(tz).plusMinutes(ws).toInstant();
                    Instant fe = day.atStartOfDay(tz).plusMinutes(we).toInstant();
                    hits.add(new WindowHit(fs, fe, we - ws, w.type(), w.price()));
                }
            }
            cursor = day.plusDays(1).atStartOfDay(tz);
        }
        // 片内匀速：按窗口分钟比例分摊，残差补给最后一个窗口 → Σ 片段电量 = 片分摊电量
        long sliceMin = Duration.between(sl.start(), sl.end()).toMinutes();
        BigDecimal allocated = BigDecimal.ZERO;
        List<ChargedFragment> out = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            WindowHit h = hits.get(i);
            BigDecimal kwh;
            if (i == hits.size() - 1) {
                kwh = sl.allocatedKwh().subtract(allocated);
                if (kwh.signum() < 0) {
                    kwh = BigDecimal.ZERO.setScale(SCALE_KWH);
                }
            } else {
                kwh = sl.allocatedKwh().multiply(BigDecimal.valueOf(h.minutes()))
                        .divide(BigDecimal.valueOf(sliceMin), SCALE_KWH, RoundingMode.HALF_UP);
                allocated = allocated.add(kwh);
            }
            BigDecimal ratio = BigDecimal.valueOf(h.minutes())
                    .divide(BigDecimal.valueOf(sl.fullIntervalMinutes()), SCALE_RATIO, RoundingMode.HALF_UP);
            out.add(new ChargedFragment(h.from(), h.to(), sl.startReading(), sl.endReading(),
                    sl.fullKwh(), h.minutes(), kwh.setScale(SCALE_KWH, RoundingMode.HALF_UP), ratio,
                    h.type(), sl.version().id(),
                    h.price().setScale(SCALE_PRICE, RoundingMode.HALF_UP), null, sl.clipped()));
        }
        return out;
    }

    private record WindowHit(Instant from, Instant to, long minutes, PeriodType type, BigDecimal price) {
    }

    /**
     * 构造某本地日的完整 TOU 窗口时间轴。跨午夜规则：
     * 尾部 [start,1440) 归当日，头部 [0,end) 归次日（由次日按「前一日规则」收编）。
     * 要求窗口恰好铺满 0~1440，既不能有空档（无法定价）也不能重叠（价格歧义）。
     */
    private List<DayWindow> daySchedule(RateVersionInfo v, LocalDate day) {
        List<DayWindow> list = new ArrayList<>();
        int dow = day.getDayOfWeek().getValue();
        int prevDow = day.minusDays(1).getDayOfWeek().getValue();
        for (TouRule r : v.rules()) {
            if (!r.crossesMidnight()) {
                if (r.appliesDow(dow)) {
                    list.add(new DayWindow(r.startMin(), r.endMin(), r.type(), r.price()));
                }
            } else {
                if (r.appliesDow(dow)) {
                    list.add(new DayWindow(r.startMin(), 1440, r.type(), r.price()));
                }
                if (r.appliesDow(prevDow)) {
                    list.add(new DayWindow(0, r.endMin(), r.type(), r.price()));
                }
            }
        }
        list.sort(Comparator.comparingInt(DayWindow::startMin));
        if (list.isEmpty()) {
            throw new BillingRuleException("费率版本#" + v.versionNo() + " 在 " + day + " 没有任何 TOU 时段");
        }
        int expected = 0;
        for (DayWindow w : list) {
            if (w.startMin() != expected) {
                throw new BillingRuleException("费率版本#" + v.versionNo() + " 在 " + day
                        + " 的 TOU 日程 " + (w.startMin() < expected ? "重叠" : "有空档")
                        + "（分钟 " + expected + " 附近）");
            }
            expected = w.endMin();
        }
        if (expected != 1440) {
            throw new BillingRuleException("费率版本#" + v.versionNo() + " 在 " + day + " 的 TOU 日程未覆盖全天");
        }
        return list;
    }

    // ------------------------------------------------------------------
    // 费用行
    // ------------------------------------------------------------------

    private List<LineSpec> buildTouLines(List<ChargedFragment> fragments) {
        // 非 TOU 单一价版本走 FLAT 聚合
        Map<PeriodType, List<ChargedFragment>> byType = new LinkedHashMap<>();
        for (PeriodType t : List.of(PeriodType.SHARP, PeriodType.PEAK, PeriodType.FLAT, PeriodType.VALLEY)) {
            byType.put(t, new ArrayList<>());
        }
        for (ChargedFragment f : fragments) {
            byType.get(f.periodType()).add(f);
        }
        List<LineSpec> lines = new ArrayList<>();
        int order = 0;
        for (PeriodType t : PeriodType.values()) {
            List<ChargedFragment> fs = byType.get(t);
            if (fs.isEmpty()) {
                continue;
            }
            lines.add(toLine("TOU", t.getLabel() + "电费", t, null, fs));
            order++;
        }
        return lines;
    }

    private List<ChargedFragment> slicesToFragments(List<Slice> slices) {
        List<ChargedFragment> out = new ArrayList<>();
        for (Slice sl : slices) {
            out.add(new ChargedFragment(sl.start(), sl.end(), sl.startReading(), sl.endReading(),
                    sl.fullKwh(), Duration.between(sl.start(), sl.end()).toMinutes(),
                    sl.allocatedKwh(), sl.allocateRatio(), null, sl.version().id(),
                    BigDecimal.ZERO.setScale(SCALE_PRICE), null, sl.clipped()));
        }
        return out;
    }

    private List<LineSpec> buildTierLines(List<ChargedFragment> sources, TierScheduleInfo tier,
                                          boolean replace) {
        List<LineSpec> lines = new ArrayList<>();
        // 各档数量：按时间顺序吃电量；恰好落在边界的片段整段归入当前档
        BigDecimal consumed = BigDecimal.ZERO.setScale(SCALE_KWH);
        int stepIdx = 0;
        int srcIdx = 0;
        // 源片段可能带剩余量（边界落在片段中间时该片段被两档共享）
        BigDecimal[] remaining = new BigDecimal[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            remaining[i] = sources.get(i).allocatedKwh();
        }
        BigDecimal grandTotal = sources.stream().map(ChargedFragment::allocatedKwh)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        while (consumed.compareTo(grandTotal) < 0 && stepIdx < tier.steps().size()) {
            TierScheduleInfo.Step step = tier.steps().get(stepIdx);
            BigDecimal cap = step.upperKwh() == null ? null
                    : step.upperKwh().subtract(step.lowerKwh());
            BigDecimal capLeft = cap;
            BigDecimal stepKwh = BigDecimal.ZERO.setScale(SCALE_KWH);
            List<ChargedFragment> stepFrags = new ArrayList<>();
            while (srcIdx < sources.size()) {
                ChargedFragment f = sources.get(srcIdx);
                BigDecimal take = remaining[srcIdx];
                if (take.signum() <= 0) {
                    srcIdx++;
                    continue;
                }
                if (capLeft != null && stepKwh.add(take).compareTo(capLeft) > 0) {
                    // 边界落在本片段内：拆成两个追溯片段，本档只取 capLeft - stepKwh
                    BigDecimal part = capLeft.subtract(stepKwh).setScale(SCALE_KWH, RoundingMode.HALF_UP);
                    if (part.signum() > 0) {
                        stepFrags.add(withTier(f, step, part));
                        stepKwh = stepKwh.add(part);
                        remaining[srcIdx] = remaining[srcIdx].subtract(part);
                    }
                    break;
                }
                stepFrags.add(withTier(f, step, take));
                stepKwh = stepKwh.add(take);
                remaining[srcIdx] = BigDecimal.ZERO.setScale(SCALE_KWH);
                srcIdx++;
                if (capLeft != null && stepKwh.compareTo(capLeft) >= 0) {
                    break;
                }
            }
            if (stepKwh.signum() > 0) {
                String label = (replace ? "阶梯电费 " : "阶梯加价 ")
                        + "第" + step.stepNo() + "档(" + strip(step.lowerKwh()) + "~"
                        + (step.upperKwh() == null ? "∞" : strip(step.upperKwh())) + "kWh)";
                BigDecimal raw = stepKwh.multiply(step.price())
                        .setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
                lines.add(new LineSpec("TIER", label, null, step.stepNo(),
                        stepKwh.setScale(SCALE_KWH), step.price().setScale(SCALE_PRICE),
                        raw, raw.setScale(SCALE_MONEY, RoundingMode.HALF_UP), stepFrags));
            }
            consumed = consumed.add(stepKwh);
            stepIdx++;
            // 恰好落在边界：stepKwh == cap，srcIdx 已指向可能为 0 剩余的片段，循环继续下一档
        }
        if (consumed.compareTo(grandTotal) < 0) {
            throw new BillingRuleException("阶梯档位不足以覆盖全部电量：已覆盖 " + consumed + "/" + grandTotal);
        }
        return lines;
    }

    private ChargedFragment withTier(ChargedFragment f, TierScheduleInfo.Step step, BigDecimal kwh) {
        return new ChargedFragment(f.start(), f.end(), f.startReading(), f.endReading(), f.fullKwh(),
                f.durationMinutes(), kwh.setScale(SCALE_KWH, RoundingMode.HALF_UP),
                f.allocateRatio(), f.periodType(), f.rateVersionId(),
                step.price().setScale(SCALE_PRICE, RoundingMode.HALF_UP), step.stepNo(), f.clipped());
    }

    private LineSpec toLine(String kind, String label, PeriodType type, Integer stepNo,
                            List<ChargedFragment> fs) {
        BigDecimal kwh = fs.stream().map(ChargedFragment::allocatedKwh)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(SCALE_KWH, RoundingMode.HALF_UP);
        BigDecimal raw = BigDecimal.ZERO.setScale(SCALE_AMOUNT);
        BigDecimal firstPrice = fs.get(0).unitPrice();
        boolean mixed = false;
        // 行内各片段用精确乘积求和，最后统一 4 位舍入（不逐片段舍入，避免误差累积）
        BigDecimal exact = BigDecimal.ZERO;
        for (ChargedFragment f : fs) {
            exact = exact.add(f.allocatedKwh().multiply(f.unitPrice()));
            if (f.unitPrice().compareTo(firstPrice) != 0) {
                mixed = true;
            }
        }
        raw = exact.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
        return new LineSpec(kind, label, type, stepNo, kwh,
                mixed ? null : firstPrice.setScale(SCALE_PRICE, RoundingMode.HALF_UP),
                raw, raw.setScale(SCALE_MONEY, RoundingMode.HALF_UP), fs);
    }

    private String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
