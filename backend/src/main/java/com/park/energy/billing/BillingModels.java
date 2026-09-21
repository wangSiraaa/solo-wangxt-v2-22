package com.park.energy.billing;

import com.park.energy.domain.DayType;
import com.park.energy.domain.PeriodType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 计费引擎输入输出模型（与 JPA 解耦，便于单测对照手工算例）。 */
public final class BillingModels {

    public record RawReading(Instant ts, BigDecimal readingKwh) {}

    public record TouRule(PeriodType periodType, int startMin, int endMin, BigDecimal pricePerKwh) {}

    /** tierIndex 从 1 开始；lowerKwh 为该档下界（不含），upperKwh 为上界（不含），null 表示无上限 */
    public record TierBand(int tierIndex, BigDecimal lowerKwh, BigDecimal upperKwh, BigDecimal surchargePerKwh) {}

    public record Tariff(String id, String code, Instant effectiveFrom, Instant effectiveTo,
                         List<TouRule> touRules, List<TierBand> tiers) {}

    public record Segment(Instant segmentStart, Instant segmentEnd,
                          BigDecimal share, BigDecimal kwh,
                          PeriodType periodType, DayType dayType,
                          String tariffId, BigDecimal pricePerKwh) {}

    /** 电量片段：来自一条相邻读数区间，可在账期/午夜/版本/时段边界处被切分 */
    public record Fragment(Instant intervalStart, Instant intervalEnd,
                           BigDecimal readingStart, BigDecimal readingEnd, BigDecimal intervalKwh,
                           List<Segment> segments) {}

    public record TierAllocation(int tierIndex, String tariffId, int segmentIndex, int fragmentIndex,
                                 BigDecimal kwh, BigDecimal cumulativeBefore, BigDecimal surchargePerKwh) {}

    public record Gap(Instant gapStart, Instant gapEnd, String reason, String detail) {}

    public record EnergyLine(String tariffId, PeriodType periodType, BigDecimal kwh,
                             BigDecimal rawAmount, BigDecimal amount) {}

    public record TierLine(String tariffId, int tierIndex, BigDecimal kwh,
                           BigDecimal rawAmount, BigDecimal amount) {}

    public record Result(Instant windowStart, Instant windowEnd,
                         List<Fragment> fragments,
                         List<EnergyLine> energyLines,
                         List<TierLine> tierLines,
                         List<TierAllocation> tierAllocations,
                         List<Gap> gaps,
                         BigDecimal totalKwh,
                         BigDecimal rawTotal,
                         BigDecimal roundedLineSum,
                         BigDecimal roundingAmount,
                         BigDecimal totalAmount,
                         boolean noReadings) {}
}
