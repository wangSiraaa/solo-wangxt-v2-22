package com.park.energy.billing;

/** 计费输入或费率配置错误（如表码倒走、费率缺口、时段重叠）。显式失败，绝不静默修正。 */
public class BillingException extends RuntimeException {
    public BillingException(String message) {
        super(message);
    }
}
