package com.park.energy.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 费率版本快照值对象（对应 rate_version + tou_period）。
 * 生效区间 [effectiveFrom, effectiveTo)，effectiveTo=null 表示至今。
 */
public record RateVersionInfo(
        long id,
        String code,
        int versionNo,
        Instant effectiveFrom,
        Instant effectiveTo,   // null = 开放区间
        boolean tou,
        BigDecimal flatPrice,
        List<TouRule> rules
) {
    public boolean contains(Instant t) {
        if (t.isBefore(effectiveFrom)) {
            return false;
        }
        return effectiveTo == null || t.isBefore(effectiveTo);
    }
}
