package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 参与计费的最小电量片段（账单可追溯的基本单位）。
 *
 * 一个片段说明：在 [start,end) 内，从原始读数区间 fullKwh 中按 allocateRatio
 * （分钟占比）分摊出 allocatedKwh，套用 periodType + rateVersionId 的 unitPrice。
 * tierStepNo 非空时这是阶梯费用行的追溯片段（同一物理电量可能同时出现在 TOU 行与阶梯行）。
 */
public record ChargedFragment(
        Instant start,
        Instant end,
        BigDecimal startReading,
        BigDecimal endReading,
        BigDecimal fullKwh,          // 所属原始读数区间电量
        long durationMinutes,        // 本片段分钟数
        BigDecimal allocatedKwh,
        BigDecimal allocateRatio,    // 分钟占比（10 位小数）
        PeriodType periodType,       // 阶梯片段为 null
        Long rateVersionId,
        BigDecimal unitPrice,
        Integer tierStepNo,          // 非 null => 阶梯行片段
        boolean clipped              // 被账月边界裁过
) {
}
