package com.park.energy.billing;

/** 尖峰平谷。命名固定，排序用于账单展示。 */
public enum PeriodType {
    SHARP("尖"),
    PEAK("峰"),
    FLAT("平"),
    VALLEY("谷");

    private final String label;

    PeriodType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
