package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * TOU 时段定义（来自某个费率版本）。按本地分钟表达，允许跨午夜。
 * dowMask: bit0=周一 … bit6=周日。
 */
public record TouRule(
        PeriodType type,
        int dowMask,
        int startMin,
        int endMin,
        BigDecimal price
) {
    public boolean appliesDow(int dow) {
        // java.time.DayOfWeek: 周一=1 … 周日=7
        return (dowMask & (1 << (dow - 1))) != 0;
    }

    public boolean crossesMidnight() {
        return startMin > endMin;
    }

    /** 展开成若干「不跨午夜」的 [fromMin, toMin) 本地分钟段。 */
    public List<int[]> toLocalWindows() {
        if (!crossesMidnight()) {
            return List.of(new int[]{startMin, endMin});
        }
        // 22:00-06:00 => 当日 [22:00,24:00) + 次日 [00:00,06:00)
        return List.of(new int[]{startMin, 1440}, new int[]{0, endMin});
    }
}
