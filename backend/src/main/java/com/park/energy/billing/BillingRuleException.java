package com.park.energy.billing;

/** 违反计费口径（费率空档、TOU 日程未覆盖全天等），试算直接失败而不是猜一个数字。 */
public class BillingRuleException extends RuntimeException {
    public BillingRuleException(String message) {
        super(message);
    }
}
