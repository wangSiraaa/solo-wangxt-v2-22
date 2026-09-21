package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 两个相邻有效读数之间的原始区间。电量 = end - start（累计表底之差）。
 * 读数缺失时不会产生本对象，而是 {@link ReadingGap}。
 */
public record ReadingInterval(
        Instant start,
        Instant end,
        BigDecimal startReading,
        BigDecimal endReading,
        BigDecimal kwh
) {
    public long durationMinutes() {
        return java.time.Duration.between(start, end).toMinutes();
    }
}
