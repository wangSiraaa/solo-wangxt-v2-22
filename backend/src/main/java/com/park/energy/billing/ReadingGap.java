package com.park.energy.billing;

import java.time.Instant;

/**
 * 读数缺口：[expectedAfter, expectedBefore] 之间没有任何读数。
 * 口径：缺口电量未知 —— 绝不当零，不计费，单独挂起并在账单上披露。
 */
public record ReadingGap(
        Instant expectedAfter,   // 缺口前最后一个读数时刻（可能为 null=账月起点前无读数）
        Instant expectedBefore,  // 缺口后第一个读数时刻
        long missingMinutes,     // 缺口分钟数
        String reason
) {
}
