package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 月度阶梯方案（账月维度）。SURCHARGE：在 TOU 电费之外按各档电量加征；
 * REPLACE：忽略 TOU，全部电量按阶梯单价计费。
 */
public record TierScheduleInfo(
        long id,
        String code,
        int versionNo,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,  // null = 至今
        PricingMode mode,
        List<Step> steps
) {
    public enum PricingMode { SURCHARGE, REPLACE }

    public record Step(int stepNo, BigDecimal lowerKwh, BigDecimal upperKwh, BigDecimal price) {
        public boolean contains(BigDecimal cumulative) {
            if (cumulative.compareTo(lowerKwh) < 0) {
                return false;
            }
            return upperKwh == null || cumulative.compareTo(upperKwh) < 0;
        }
    }

    public boolean appliesToMonth(LocalDate firstOfMonth) {
        if (firstOfMonth.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || firstOfMonth.isBefore(effectiveTo);
    }
}
